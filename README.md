# Etapas do projeto previstas:

1. Comunicação básica UDP ✓
2. Definir protocolo ✓
3. Implementar GET ✓
4. Criar protocolo simples textual ✓
5. Transformar o protocolo para usar packets binários ✓
6. Implementar envio de arquivo sem confiabilidade ✓
7. Implementar checksum ✓
8. Implementar controle de sequência ✓
9. Implementar simulação de perda ✓
10. Implementar retransmissão

---

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

| Flag         | Valor  | Descrição                          |
|--------------|--------|------------------------------------|
| `FLAG_DATA`  | `0x01` | Segmento de dados do arquivo       |
| `FLAG_ACK`   | `0x02` | Confirmação de recebimento         |
| `FLAG_NACK`  | `0x03` | Solicitação de retransmissão       |
| `FLAG_ERROR` | `0x04` | Mensagem de erro do servidor       |
| `FLAG_END`   | `0x05` | Último pacote da transmissão       |
| `FLAG_GET`   | `0x06` | Requisição de arquivo pelo cliente |
| `FLAG_START` | `0x07` | Resposta do servidor com metadados |

### Integridade

O checksum é calculado com CRC32 sobre o cabeçalho (sequenceNumber + flags + dataLength) concatenado com o payload. Isso garante detecção de corrupção tanto nos dados quanto nos campos de controle. A verificação ocorre em `deserialize()`: se o checksum não bater, uma exceção é lançada e o pacote é descartado.

### Tamanho máximo do payload

O tamanho do payload por pacote é calculado a partir do MTU Ethernet (1500 bytes), descontando os cabeçalhos IP (20 bytes), UDP (8 bytes) e o cabeçalho do `Packet` (11 bytes), resultando em **1461 bytes** por segmento. Isso evita fragmentação IP.

---

# Etapas da comunicação cliente-servidor

### Inicialização dos endpoints

O servidor cria um socket associado à porta `9876`, fazendo com que o sistema operacional direcione para ele todos os datagramas destinados a essa porta. O cliente cria um socket com uma porta efêmera atribuída automaticamente pelo SO e resolve o endereço do servidor (`localhost`). Não há estabelecimento de conexão — o modelo é puramente baseado em troca de datagramas, conforme o UDP.

---

### Requisição GET (cliente → servidor)

O cliente constrói um pacote com `FLAG_GET` e o nome do arquivo como payload (codificado em UTF-8). Esse pacote é serializado e enviado via `socket.send()`.

```
Packet[seq=0, flags=FLAG_GET, payload="arquivo.txt"]
```

---

### Processamento no servidor

O servidor permanece bloqueado em `receive()` até a chegada de um datagrama. Ao receber, deserializa os bytes em um objeto `Packet` e verifica o checksum. Se a flag for `FLAG_GET`, extrai o nome do arquivo do payload e inicia o processamento.

O servidor então:

1. Verifica se o arquivo existe no sistema de arquivos
2. Calcula o tamanho total (`fileSize`) e o número de segmentos (`numSegments`)
3. Envia um pacote `FLAG_START` com os metadados
4. Inicia o envio sequencial dos segmentos

Se o arquivo não existir, responde com um pacote `FLAG_ERROR` contendo a mensagem de erro.

---

### Resposta START (servidor → cliente)

O servidor envia um pacote com `FLAG_START` cujo payload contém os metadados da transferência no formato:

```
"fileSize|numSegments"
```

Esse pacote informa ao cliente quantos segmentos esperar, permitindo que ele detecte perdas ao final da recepção.

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
3. Se `FLAG_END`: encerra o loop

O `TreeMap` garante que os segmentos ficam ordenados por sequência independentemente da ordem de chegada.

---

### Simulação de perda

O cliente implementa descarte intencional de segmentos para simular condições reais de rede. Antes de armazenar cada segmento recebido, um valor aleatório entre 0.0 e 1.0 é comparado com a constante `LOSS_RATE` (atualmente `0.2`, ou seja, 20%). Se o valor sorteado for menor que `LOSS_RATE`, o segmento é descartado e logado no console:

```
[Cliente] DESCARTADO seq=3 (simulação de perda)
```

O segmento não é inserido no mapa, como se nunca tivesse chegado.

---

### Montagem e salvamento do arquivo

Após receber o `FLAG_END`, o cliente concatena os payloads armazenados no `TreeMap` em ordem crescente de sequência, formando o conteúdo completo do arquivo. O arquivo é gravado em disco com o prefixo `received_`:

```
received_arquivo.txt
```

**Limitação atual:** se segmentos foram descartados pela simulação de perda, o arquivo é salvo incompleto. Essa limitação será resolvida na próxima etapa com a implementação da retransmissão.

---

# Fluxo de chamadas

## Servidor

```
main()
 └── start()
      └── loop:
           ├── receivePacket()
           │    └── socket.receive()
           ├── Packet.deserialize()
           │    └── verifica checksum
           └── handleGet()
                ├── File.exists()
                ├── Packet(FLAG_START) → socket.send()
                └── sendFile()
                     ├── FileInputStream.read()
                     ├── Packet(FLAG_DATA, seq, chunk) → socket.send()
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
                ├── loop:
                │    ├── receivePacket()
                │    ├── Packet.deserialize()
                │    ├── shouldDrop()  →  descarta ou armazena no TreeMap
                │    └── break em FLAG_END
                ├── concatena payloads do TreeMap
                └── FileOutputStream.write()
```