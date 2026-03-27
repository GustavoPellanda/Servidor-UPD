# Etapas do projeto previstas:

1. Comunicação básica UDP ✓
2. Definir protocolo ✓
3. Implementar GET ✓
4. Criar protocolo simples textual ✓
5. Transformar o protocolo para usar packets binários
6. Implementar envio de arquivo sem confiabilidade *(em andamento)*
7. Implementar checksum
8. Implementar controle de sequência
9. Implementar retransmissão
10. Implementar simulação de perda

---

# Etapas da comunicação cliente - servidor:

### Inicialização dos endpoints

O servidor cria um socket associado à porta `9876`, fazendo com que o sistema operacional direcione para ele todos os datagramas destinados a essa porta. O cliente cria um socket com uma porta efêmera atribuída automaticamente e resolve o endereço do servidor (`localhost`). Nesse ponto, ambos estão prontos para trocar dados, sem estabelecimento de conexão, conforme o modelo UDP.

---

### Envio da requisição pelo cliente

O cliente constrói uma requisição no formato:

```
GET|nome_do_arquivo
```

Essa mensagem é serializada em bytes e encapsulada em um `DatagramPacket`, contendo o endereço IP e a porta do servidor. O envio é realizado através de `socket.send()`, sem garantias de entrega, ordem ou duplicação.

---

### Recepção no servidor

O servidor permanece bloqueado em `receive()` até a chegada de um datagrama. Ao receber:

* Extrai o conteúdo da mensagem
* Identifica o comando (`GET`)
* Obtém o endereço e a porta do cliente

---

### Processamento da requisição (GET)

Ao receber um `GET`, o servidor:

1. Extrai o nome do arquivo
2. Verifica sua existência no sistema de arquivos
3. Calcula os parâmetros da transferência:

   * Tamanho total do arquivo (`fileSize`)
   * Número de segmentos (`numSegments`)
   * Tamanho de cada segmento (`chunkSize`)

---

### Envio da resposta START (negociação)

Se o arquivo existir, o servidor responde com:

```
START|fileSize|numSegments|chunkSize
```

Essa mensagem representa uma **fase de negociação**, onde o cliente passa a conhecer:

* Quantidade de dados esperados
* Estrutura da transmissão
* Granularidade dos segmentos

Caso o arquivo não exista:

```
ERROR|Arquivo nao encontrado
```

---

### Início da transmissão de dados (stream UDP)

Após enviar o `START`, o servidor inicia o envio sequencial dos dados do arquivo.

O arquivo é lido em blocos de tamanho fixo (`CHUNK_SIZE`) e cada bloco é enviado como um datagrama independente no formato:

```
DATA|seq|conteudo
```

Onde:

* `seq` representa o número sequencial do segmento
* `conteudo` representa os bytes lidos do arquivo (atualmente tratados como texto)

Essa etapa caracteriza um **stream de dados sobre UDP**, ainda sem garantias de confiabilidade.

---

### Recepção contínua no cliente

Ao receber a mensagem `START`, o cliente entra em modo de recepção contínua:

* Executa um loop de recepção baseado em `numSegments`
* Recebe múltiplos datagramas sequenciais
* Exibe os segmentos recebidos

Exemplo:

```
[Cliente] Segmento recebido: DATA|0|...
[Cliente] Segmento recebido: DATA|1|...
```

Neste estágio:

* Não há reordenação
* Não há detecção de perda
* Não há validação de integridade

---

# Fluxo de chamadas:

## Servidor

```text
main()
 └── start()
      └── loop:
           ├── receivePacket()
           │    └── socket.receive()
           ├── extractMessage()
           ├── logReceived()
           ├── Protocol.isGet()
           ├── handleGet()
           │    ├── Protocol.extractFilename()
           │    ├── File.exists()
           │    ├── cálculo de fileSize, numSegments, chunkSize
           │    ├── buildStartResponse()
           │    ├── socket.send() (START)
           │    └── sendFileChunks()
           │         ├── FileInputStream.read()
           │         ├── montagem "DATA|seq|payload"
           │         └── socket.send()
```

---

## Cliente

```text
main()
 └── start()
      ├── readUserInput()
      ├── Protocol.buildGetRequest()
      ├── sendMessage()
      │    └── socket.send()
      ├── handleServerResponse()
      │    ├── receivePacket()
      │    │    └── socket.receive()
      │    ├── extractMessage()
      │    ├── Protocol.isStart()
      │    └── handleStart()
      │         ├── parseMessage()
      │         └── receiveFile()
      │              ├── loop (numSegments)
      │              ├── receivePacket()
      │              └── extractMessage()
      └── loop
```

---
