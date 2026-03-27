# Etapas do projeto previstas:

1. Comunicação básica UDP ✓
2. Definir protocolo
3. Implementar GET
4. Implementar envio de arquivo sem confiabilidade
5. Implementar checksum
6. Implementar controle de sequência
7. Implementar retransmissão
8. Implementar simulação de perda

---

# Etapas da comunicação cliente - servidor:

### Inicialização dos endpoints

O servidor cria um socket associado à porta `9876`, fazendo com que o sistema operacional direcione para ele todos os datagramas destinados a essa porta. O cliente cria um socket com uma porta efêmera atribuída automaticamente e resolve o endereço do servidor (`localhost`). Nesse ponto, ambos estão prontos para trocar dados, mas não existe qualquer conexão estabelecida entre eles.

### Envio da mensagem pelo cliente

O cliente converte a mensagem digitada em bytes e a encapsula em um `DatagramPacket`, definindo o IP e a porta do servidor como destino. Ao chamar `send()`, o datagrama é entregue ao sistema operacional, que o envia pela rede **sem garantia de entrega, ordem ou confirmação**.

### Recepção no servidor

O servidor permanece bloqueado em `receive()` até a chegada de um datagrama. Quando isso ocorre, o sistema operacional entrega o pacote ao socket correto com base na porta. O servidor recebe tanto os dados quanto o IP e a porta de origem do cliente.

### Processamento e resposta

O servidor extrai a mensagem, constrói uma resposta e cria um novo datagrama usando como destino o endereço e a porta do cliente. Em seguida, envia esse pacote pela rede, novamente **sem garantias**.

### Recepção no cliente

O cliente aguarda a resposta com `receive()`. Se o datagrama chegar dentro do tempo limite, ele extrai e exibe a mensagem; caso contrário, ocorre **timeout**, refletindo a natureza não confiável do UDP.

### Natureza da comunicação

Cada troca é independente e não há estado compartilhado. A comunicação consiste apenas no envio e recepção de datagramas, sem conexão persistente ou controle automático de confiabilidade.

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
           ├── sendResponse()
           │    ├── buildResponseText()
           │    └── socket.send()
```

## Cliente

```text
main()
 └── start()
      ├── readUserInput()
      ├── sendMessage()
      │    └── socket.send()
      ├── receiveAndDisplayResponse()
      │    ├── receivePacket()
      │    │    └── socket.receive()
      │    └── extractMessage()
      └── loop
```
