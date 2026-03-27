import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

public class Server {

    private static final int PORT = 9876;        // Porta onde o servidor escuta por mensagens dos clientes
    private static final int BUFFER_SIZE = 1024; // Tamanho do buffer para receber mensagens (em bytes)
    private final DatagramSocket socket;         // Socket UDP usado para enviar e receber datagramas
    private boolean running;                     // Flag para controlar o loop principal do servidor

    public Server() throws SocketException {     // Pode lançar SocketException se a porta já estiver em uso ou houver problema de rede
        this.socket  = new DatagramSocket(PORT); // Cria um socket UDP vinculado à porta especificada
        this.running = false;                    // Inicialmente, o servidor não está rodando até que start() seja chamado
    }

    // Recebe um datagrama do cliente:
    private DatagramPacket receivePacket() throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE]; // Buffer para armazenar os dados recebidos
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length); // Cria um pacote para receber os dados, associando o buffer a ele
        socket.receive(packet); // Bloqueia a thread até que um pacote seja recebido, preenchendo o objeto 'packet' com os dados e informações do remetente
        return packet;
    }

    // Extrai a mensagem do pacote recebido:
    private String extractMessage(DatagramPacket packet) {
        return new String(packet.getData(), 0, packet.getLength());
    }

    // Constrói o texto da resposta com base na mensagem original recebida:
    private String buildResponseText(String originalMessage) {
        return "Mensagem recebida do cliente: \"" + originalMessage + "\"";
    }

    // Envia uma resposta de volta ao cliente:
    private void sendResponse(String originalMessage, InetAddress clientAddress, int clientPort) throws IOException {
        String responseText  = buildResponseText(originalMessage);
        byte[] responseBytes = responseText.getBytes();

        // Cria um pacote de resposta, associando os bytes da resposta ao endereço e porta do cliente:
        DatagramPacket response = new DatagramPacket(
            responseBytes,
            responseBytes.length,
            clientAddress,
            clientPort
        );

        socket.send(response);
        System.out.println("[Servidor] Resposta enviada -> \"" + responseText + "\"");
    }

    // Loga detalhes do pacote recebido, incluindo o endereço IP, porta e conteúdo da mensagem:
    private void logReceived(String message, DatagramPacket packet) {
        System.out.printf("[Servidor] Pacote recebido de %s:%d -> \"%s\"%n",
            packet.getAddress().getHostAddress(),
            packet.getPort(),
            message
        );
    }

    // Inicialização e loop principal do servidor:
    public void start() {
        running = true;
        System.out.println("[Servidor] Escutando na porta " + PORT + "...");

        while (running) {
            try {
                DatagramPacket request = receivePacket();                       // Bloqueia a thread até receber um pacote
                String message = extractMessage(request);                       // Extrai a mensagem do pacote recebido
                logReceived(message, request);                                  // Loga detalhes do pacote recebido
                sendResponse(message, request.getAddress(), request.getPort()); // Envia resposta de volta ao cliente
            } catch (IOException e) {
                if (running) {
                    System.err.println("[Servidor] Erro ao processar pacote: " + e.getMessage());
                }
            }
        }
    }

    // Encerra o servidor:
    public void stop() {
        running = false; // Interrompe o loop principal
        socket.close();  // Fecha o socket, o que fará com que receive() lance uma exceção e saia do loop
        System.out.println("[Servidor] Encerrado.");
    }

    public static void main(String[] args) throws SocketException {
        Server server = new Server();
        server.start();
    }
}