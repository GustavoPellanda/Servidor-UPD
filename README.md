# Etapas do projeto previstas:

1. Comunicação básica UDP ✓
2. Definir protocolo ✓
3. Implementar GET ✓
4. Implementar envio de arquivo sem confiabilidade
5. Implementar checksum
6. Implementar controle de sequência
7. Implementar retransmissão
8. Implementar simulação de perda

---

# Etapas da comunicação cliente - servidor:

### Inicialização dos endpoints

O servidor cria um socket associado à porta `9876`, fazendo com que o sistema operacional direcione para ele todos os datagramas destinados a essa porta. O cliente cria um socket com uma porta efêmera atribuída automaticamente e resolve o endereço do servidor (`localhost`). Nesse ponto, ambos estão prontos para trocar dados, mas não existe qualquer conexão estabelecida entre eles.

### Envio da requisição pelo cliente

O cliente constrói uma requisição seguindo o protocolo definido, no formato:

```
GET|nome_do_arquivo
```

Essa string é convertida em bytes e encapsulada em um `DatagramPacket`, contendo o IP e a porta do servidor. Ao chamar `send()`, o datagrama é entregue ao sistema operacional, que o transmite pela rede **sem garantias de entrega, ordem ou confiabilidade**.

### Recepção no servidor

O servidor permanece bloqueado em `receive()` até a chegada de um datagrama. Quando isso ocorre, o sistema operacional entrega o pacote ao socket correto com base na porta.

O servidor então:

* Extrai a mensagem recebida
* Identifica o tipo de comando (ex: `GET`)
* Obtém o endereço e porta do cliente a partir do pacote

### Processamento da requisição (GET)

Ao receber uma mensagem `GET`, o servidor:

1. Extrai o nome do arquivo solicitado
2. Verifica se o arquivo existe no sistema de arquivos
3. Executa uma das ações:

* **Se o arquivo existir:**

  ```
  OK|nome_do_arquivo
  ```

* **Se o arquivo não existir:**

  ```
  ERROR|Arquivo nao encontrado
  ```

Essa resposta é construída conforme o protocolo e enviada ao cliente.

### Envio da resposta

O servidor encapsula a resposta em um novo `DatagramPacket`, utilizando o IP e a porta de origem do cliente, e realiza o envio via `socket.send()`.

Assim como no envio do cliente, essa transmissão ocorre **sem garantias de entrega ou confirmação**.

### Recepção no cliente

O cliente aguarda a resposta utilizando `receive()` com timeout configurado.

* Se a resposta chegar dentro do tempo limite:

  * A mensagem é extraída
  * O resultado é exibido ao usuário

* Caso contrário:

  * Ocorre timeout
  * O cliente informa possível perda de pacote

### Natureza da comunicação

A comunicação continua sendo **não orientada à conexão**, porém agora possui uma **camada de protocolo de aplicação**, responsável por:

* Definir comandos (`GET`)
* Padronizar respostas (`OK`, `ERROR`)
* Dar significado semântico às mensagens

Cada troca ainda é independente, mas agora segue regras explícitas definidas pelo protocolo.

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
           │    ├── verificação do arquivo (File.exists)
           │    ├── buildOkResponse() ou ERROR
           │    └── socket.send()
```

## Cliente

```text
main()
 └── start()
      ├── readUserInput()
      ├── Protocol.buildGetRequest()
      ├── sendMessage()
      │    └── socket.send()
      ├── receiveAndDisplayResponse()
      │    ├── receivePacket()
      │    │    └── socket.receive()
      │    └── extractMessage()
      └── loop
```
