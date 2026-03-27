import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Scanner;

import Protocol.Protocol;
 
public class Client {
 
    private static final String SERVER_HOST = "localhost"; // Endereço do servidor
    private static final int SERVER_PORT = 9876;           // Porta do servidor
    private static final int BUFFER_SIZE = 1024;           // Tamanho do buffer de recepção (em bytes)
    private static final int TIMEOUT_MS  = 5000;           // Tempo máximo de espera pela resposta do servidor (em ms)
    private static final String EXIT_COMMAND = "sair";     // Comando que encerra o cliente
    private final DatagramSocket socket;                   // Socket UDP do cliente — porta local é atribuída automaticamente pelo SO
    private final InetAddress serverAddress;               // Endereço resolvido do servidor (hostname -> InetAddress)
    private final Scanner scanner;                         // Leitura da entrada do usuário no console
 
    public Client() throws IOException {
        this.socket = new DatagramSocket(); // Cria o socket sem porta fixa, o SO escolhe uma porta disponível
        this.socket.setSoTimeout(TIMEOUT_MS); // Configura timeout (sem isso, receive() bloquearia indefinidamente se o pacote fosse perdido)
        this.serverAddress = InetAddress.getByName(SERVER_HOST); // Resolve o hostname para InetAddress uma única vez no construtor
        this.scanner = new Scanner(System.in);  // Scanner para ler a entrada do usuário a partir do console
    }
 
    // Serializa uma mensagem em bytes e a envia ao servidor em um datagrama:
    private void sendMessage(String message) throws IOException {
        byte[] data = message.getBytes();
        
        // Cria um pacote de envio, associando os bytes da mensagem ao endereço e porta do servidor:
        DatagramPacket packet = new DatagramPacket(
            data, 
            data.length, 
            serverAddress, 
            SERVER_PORT
        );
 
        socket.send(packet);
        System.out.println("[Cliente] Mensagem enviada: \"" + message + "\"");
    }
 
    // Aguarda a resposta do servidor e a exibe no console:
    private DatagramPacket receivePacket() throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE]; // Buffer para armazenar os dados recebidos
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length); // Cria um pacote para receber os dados, associando o buffer a ele
        socket.receive(packet); // Lança SocketTimeoutException se TIMEOUT_MS expirar sem resposta
        return packet;
    }
 
    // Extrai a mensagem do pacote recebido:
    private String extractMessage(DatagramPacket packet) {
        return new String(packet.getData(), 0, packet.getLength()); // Usa getLength() para não incluir bytes de padding do buffer
    }
 
    // Aguarda a resposta do servidor e a exibe no console:
    private void receiveAndDisplayResponse() throws IOException {
        try {
            DatagramPacket responsePacket = receivePacket();
            String response = extractMessage(responsePacket);
            System.out.println("[Cliente] Resposta do servidor: \"" + response + "\"");
        } catch (SocketTimeoutException e) {
            System.out.printf("[Cliente] Sem resposta após %dms. Pacote pode ter sido perdido.%n", TIMEOUT_MS);
        }
    }

    // Lê uma linha digitada pelo usuário no console:
    private String readUserInput() {
        System.out.print("> ");
        return scanner.nextLine();
    }

    // Verifica se o usuário digitou o comando de encerramento:
    private boolean shouldExit(String input) {
        return input.equalsIgnoreCase(EXIT_COMMAND);
    }
 
    // Libera os recursos do cliente ao encerrar:
    private void close() {
        System.out.println("[Cliente] Encerrando.");
        socket.close();
        scanner.close();
    }
 
    // Loop principal do cliente:
    public void start() {
        System.out.println("[Cliente] Conectado a " + SERVER_HOST + ":" + SERVER_PORT);
        System.out.println("[Cliente] Digite uma mensagem (ou '" + EXIT_COMMAND + "' para encerrar):");
 
        while (true) {
            String message = readUserInput();
 
            if (shouldExit(message)) break;
 
            try {
                String request = Protocol.buildGetRequest(message);
                sendMessage(request);
                receiveAndDisplayResponse();
            } catch (IOException e) {
                System.err.println("[Cliente] Erro de I/O: " + e.getMessage());
            }
        }
 
        close();
    }
 
    public static void main(String[] args) throws IOException {
        Client client = new Client();
        client.start();
    }
}