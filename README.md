# Visão geral do protocolo

O protocolo opera inteiramente sobre UDP e define sua própria camada de confiabilidade. Toda comunicação entre cliente e servidor — incluindo requisições, respostas de controle, dados e erros — é encapsulada em pacotes binários do tipo `Packet`. Não há troca de mensagens textuais em nenhuma etapa.

---

# Estrutura do pacote (`Packet`)

Cada datagrama enviado segue o seguinte formato binário:

```
[ 0  1  2  3 ] → sequenceNumber  (int, 4 bytes)
[ 4  5  6  7 ] → checksum        (int, 4 bytes, CRC32)
[ 8           ] → flags           (byte, 1 byte)
[ 9  10       ] → dataLength      (short, 2 bytes)
[ 11 ...      ] → payload         (N bytes)
```

Tamanho total do cabeçalho: **11 bytes fixos**.

O campo `flags` identifica o tipo do pacote:

| Flag         | Valor  | Descrição                           |
|--------------|--------|-------------------------------------|
| `FLAG_DATA`  | `0x01` | Segmento de dados do arquivo        |
| `FLAG_ACK`   | `0x02` | Confirmação de recebimento          |
| `FLAG_NACK`  | `0x03` | Solicitação de retransmissão        |
| `FLAG_ERROR` | `0x04` | Mensagem de erro do servidor        |
| `FLAG_END`   | `0x05` | Fim da transmissão ou retransmissão |
| `FLAG_GET`   | `0x06` | Requisição de arquivo pelo cliente  |
| `FLAG_START` | `0x07` | Resposta do servidor com metadados  |

### Integridade

O checksum é calculado com CRC32 sobre o cabeçalho (sequenceNumber + flags + dataLength) concatenado com o payload. Isso garante detecção de corrupção tanto nos dados quanto nos campos de controle. A verificação ocorre em `deserialize()`: se o checksum não bater, uma exceção é lançada e o pacote é descartado.

### Tamanho máximo do payload

O tamanho do payload por pacote é calculado a partir do MTU Ethernet (1500 bytes), descontando os cabeçalhos IP (20 bytes), UDP (8 bytes) e o cabeçalho do `Packet` (11 bytes), resultando em **1461 bytes** por segmento. Isso evita fragmentação IP.

---

# Arquitetura do servidor — multi-cliente com Thread Pool

O servidor suporta múltiplos clientes simultâneos por meio de dois mecanismos combinados: **demultiplexação por sessão** e **processamento paralelo via pool de threads**. A responsabilidade do servidor está dividida em duas camadas distintas.

---

### Camada 1 — Thread principal: recepção e demultiplexação

O servidor possui um único socket UDP vinculado à porta `9876`. Em UDP, diferentemente do TCP, não existe uma conexão por cliente — todos os datagramas de todos os clientes chegam pelo mesmo socket. Cabe ao servidor distingui-los.

A thread principal executa um loop contínuo bloqueado em `socket.receive()`. A cada datagrama recebido, ela extrai o endereço IP e a porta de origem e forma uma chave de identificação única do cliente no formato `"ip:porta"`:

```
"192.168.0.10:52341"  →  Cliente A
"192.168.0.17:61024"  →  Cliente B
```

Essa chave é usada para consultar um `ConcurrentHashMap<String, ClientSession>` que mantém o registro de todas as sessões ativas. Se o cliente já possui uma sessão, ela é recuperada. Se for o primeiro contato, uma nova `ClientSession` é criada e registrada atomicamente via `computeIfAbsent()` — operação que garante que dois pacotes simultâneos do mesmo cliente nunca resultem em duas sessões distintas.

Após identificar a sessão correta, a thread principal **não processa o pacote ela mesma**. Ela apenas despacha o par `(sessão, pacote)` para o pool de threads e retorna imediatamente ao `receive()`, pronta para atender o próximo cliente. Isso garante que nenhum cliente bloqueie os demais durante o processamento.

```
Thread principal
│
├── receive() ← bloqueia até chegar qualquer datagrama
├── extrai ip:porta → forma clientKey
├── sessions.computeIfAbsent(clientKey, ...) → obtém ou cria sessão
├── pool.submit(() -> session.handle(packet)) → despacha para pool
└── volta ao receive() imediatamente
```

---

### Camada 2 — Pool de threads: processamento por sessão

O pool é criado com `Executors.newFixedThreadPool(10)`, o que significa que até 10 requisições podem ser processadas em paralelo. Cada tarefa submetida ao pool é uma chamada a `session.handle(packet)`.

O método `handle()` da `ClientSession` é declarado como `synchronized`. Isso garante que, mesmo que o mesmo cliente envie dois pacotes rapidamente (por exemplo, um `GET` seguido de um `NACK`), as duas tarefas serão executadas em sequência dentro da mesma sessão, evitando condições de corrida no estado interno da sessão (como o campo `lastFilename`). Clientes diferentes, porém, possuem sessões distintas e não se bloqueiam mutuamente.

```
Pool de threads (até 10 paralelas)
│
├── Thread 1: sessão A → handleGet()  → lê arquivo → envia chunks para A
├── Thread 2: sessão B → handleGet()  → lê arquivo → envia chunks para B
├── Thread 3: sessão C → handleGet()  → lê arquivo → envia chunks para C
└── Thread 4: sessão A → handleNack() → retransmite segmentos para A  ← aguarda Thread 1 (synchronized)
```

O socket é **compartilhado** entre todas as sessões e threads. Em UDP, o mesmo socket pode enviar para múltiplos destinos, pois cada `DatagramPacket` carrega o endereço e a porta do destinatário. Como `DatagramSocket.send()` é thread-safe na JVM, não é necessário sincronizar os envios.

---

### Isolamento de estado entre clientes

Cada `ClientSession` encapsula exclusivamente o estado da transferência do seu cliente: o endereço e porta de destino, e o nome do último arquivo solicitado (`lastFilename`), usado para atender retransmissões via `NACK`. Não existe estado compartilhado entre sessões diferentes. Isso resolve um problema presente na versão single-cliente, onde `lastFilename`, `lastAddr` e `lastPort` eram campos do `Server` e seriam sobrescritos por um segundo cliente antes que o primeiro concluísse sua retransmissão.

---

# Etapas da comunicação cliente-servidor

### Inicialização dos endpoints

O servidor cria um socket associado à porta `9876`, fazendo com que o sistema operacional direcione para ele todos os datagramas destinados a essa porta. O cliente cria um socket com uma porta efêmera atribuída automaticamente pelo SO e resolve o endereço do servidor. Não há estabelecimento de conexão — o modelo é puramente baseado em troca de datagramas, conforme o UDP.

---

### Requisição GET (cliente → servidor)

O cliente constrói um pacote com `FLAG_GET` e o nome do arquivo como payload (codificado em UTF-8). Esse pacote é serializado e enviado via `socket.send()`.

```
Packet[seq=0, flags=FLAG_GET, payload="arquivo.txt"]
```

---

### Processamento no servidor

A thread principal recebe o datagrama, identifica o cliente, obtém ou cria a sessão correspondente e despacha para o pool. A thread do pool invoca `session.handle()`, que verifica a flag `FLAG_GET` e chama `handleGet()`.

O servidor então:

1. Verifica se o arquivo existe no sistema de arquivos
2. Calcula o tamanho total (`fileSize`) e o número de segmentos (`numSegments`)
3. Calcula o hash MD5 do arquivo completo para verificação de integridade pelo cliente
4. Envia um pacote `FLAG_START` com os metadados
5. Inicia o envio sequencial dos segmentos
6. Armazena o nome do arquivo em `lastFilename` para uso em eventuais retransmissões via NACK

Se o arquivo não existir, responde com um pacote `FLAG_ERROR` contendo a mensagem de erro.

---

### Resposta START (servidor → cliente)

O servidor envia um pacote com `FLAG_START` cujo payload contém os metadados da transferência no formato:

```
"fileSize|numSegments|md5"
```

Esse pacote informa ao cliente quantos segmentos esperar e o hash MD5 do arquivo completo, permitindo que ele detecte perdas e valide a integridade ao final.

---

### Transmissão dos segmentos (servidor → cliente)

O servidor lê o arquivo em blocos de `CHUNK_SIZE` bytes (1461 bytes) e envia cada bloco como um pacote `FLAG_DATA` com número de sequência incrementado a partir de 0. Após todos os blocos, envia um pacote `FLAG_END` sinalizando o fim da transmissão.

```
Packet[seq=0, flags=FLAG_DATA, payload=<1461 bytes>]
Packet[seq=1, flags=FLAG_DATA, payload=<1461 bytes>]
...
Packet[seq=N, flags=FLAG_END,  payload=<vazio>]
```

---

### Recepção no cliente

Ao receber o `FLAG_START`, o cliente entra em loop de recepção. Para cada datagrama recebido:

1. Deserializa em `Packet` e valida o checksum
2. Se `FLAG_DATA`: verifica se o segmento deve ser descartado (simulação de perda) e, caso não, armazena o payload em um `TreeMap<Integer, byte[]>` indexado pelo número de sequência
3. Se `FLAG_END`: encerra o loop de recepção e verifica os faltantes

O `TreeMap` garante que os segmentos ficam ordenados por sequência independentemente da ordem de chegada.

---

### Simulação de perda

O cliente implementa descarte intencional de segmentos para simular condições reais de rede. Antes de armazenar cada segmento recebido, um valor aleatório entre 0.0 e 1.0 é comparado com a taxa de perda configurada pelo usuário no momento da inicialização. Se o valor sorteado for menor que a taxa, o segmento é descartado e logado no console:

```
[Cliente] DESCARTADO seq=3 (simulação de perda)
```

O segmento não é inserido no mapa, como se nunca tivesse chegado. A simulação de perda se aplica tanto à recepção inicial quanto às retransmissões.

---

### Detecção de perda e solicitação de retransmissão

Após cada ciclo de recepção, o cliente compara as chaves do `TreeMap` com o intervalo esperado `[0, numSegments)`. Qualquer número de sequência ausente é adicionado ao conjunto `missing`. Se o conjunto não estiver vazio e o limite de tentativas não tiver sido atingido, o cliente envia um pacote `FLAG_NACK` cujo payload contém os números de sequência faltantes serializados como uma string separada por vírgulas:

```
Packet[seq=0, flags=FLAG_NACK, payload="2,5,11"]
```

O servidor, ao receber o `FLAG_NACK`, usa `RandomAccessFile` para saltar diretamente para a posição de cada segmento faltante no arquivo (`seq * CHUNK_SIZE`) e retransmite apenas esses chunks. Ao final, envia um novo `FLAG_END` para sinalizar o término da retransmissão.

O cliente repete esse ciclo até que todos os segmentos sejam recebidos ou o limite de `MAX_RETRIES` tentativas seja atingido. Se o limite for esgotado com segmentos ainda faltando, a transferência é abandonada e o arquivo não é salvo.

---

### Montagem e salvamento do arquivo

Somente após confirmar que todos os segmentos foram recebidos, o cliente itera sobre os valores do `TreeMap` em ordem crescente de sequência e grava cada chunk sequencialmente em disco, formando o arquivo completo. O arquivo é salvo com o prefixo `received_`:

```
received_arquivo.txt
```

Em seguida, o cliente calcula o MD5 do arquivo salvo e compara com o hash recebido no pacote `FLAG_START`. Se os hashes coincidirem, a integridade do arquivo é confirmada. Caso contrário, um erro de integridade é reportado ao usuário.

---

# Fluxo de chamadas

## Servidor

```
main()
 └── start()
      └── loop (thread principal):
           ├── receivePacket()
           │    └── socket.receive()
           ├── extrai ip:porta → clientKey
           ├── sessions.computeIfAbsent(clientKey) → obtém ou cria ClientSession
           └── pool.submit(() -> session.handle(packet))
                │
                └── (thread do pool) session.handle()       [synchronized]
                     ├── handleGet()                        (FLAG_GET)
                     │    ├── File.exists()
                     │    ├── calculateMD5()
                     │    ├── Packet(FLAG_START) → socket.send()
                     │    └── sendFile()
                     │         ├── FileInputStream.read()
                     │         ├── Packet(FLAG_DATA, seq, chunk) → socket.send()
                     │         └── Packet(FLAG_END) → socket.send()
                     └── handleNack()                       (FLAG_NACK)
                          ├── extrai lista de sequências do payload
                          ├── RandomAccessFile.seek(seq * CHUNK_SIZE)
                          ├── Packet(FLAG_DATA, seq, chunk) → socket.send()  (para cada faltante)
                          └── Packet(FLAG_END) → socket.send()
```

## Cliente

```
main()
 └── start()
      ├── readUserInput()
      ├── Packet(FLAG_GET, filename) → sendPacket()
      └── handleServerResponse()
           ├── receivePacket()
           ├── Packet.deserialize()
           └── receiveFile()
                └── loop (até MAX_RETRIES):
                     ├── loop de recepção:
                     │    ├── receivePacket()
                     │    ├── Packet.deserialize()
                     │    ├── shouldDrop() → descarta ou armazena no TreeMap
                     │    └── break em FLAG_END ou timeout
                     ├── compara TreeMap com [0, numSegments) → conjunto missing
                     ├── se missing vazio → break
                     ├── requestRetransmission() → Packet(FLAG_NACK) → sendPacket()
                     └── (repete o ciclo)
                └── saveFile()
                     ├── FileOutputStream.write() → grava chunks em ordem do TreeMap
                     └── calculateMD5() → compara com hash recebido no START
```