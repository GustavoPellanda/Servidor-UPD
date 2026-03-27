import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

import Protocol.Protocol;

public class Server {

    private static final int PORT = 9876;        // Porta onde o servidor escuta por mensagens dos clientes
    private static final int BUFFER_SIZE = 1024; // Tamanho do buffer para receber mensagens (em bytes)
    private final DatagramSocket socket;         // Socket UDP usado para enviar e receber datagramas
    private boolean running;                     // Flag para controlar o loop principal do servidor

    public Server() throws SocketException {     // Pode lançar SocketException se a porta já estiver em uso ou houver problema de rede
        this.socket  = new DatagramSocket(PORT); // Cria um socket UDP vinculado à porta especificada
        this.running = false;                    // Inicialmente, o servidor não está rodando até que start() seja chamado
    }

    // ---- Métodos relacionados ao protocolo de comunicação ----

    // Processa um comando GET recebido:
    private void handleGet(String message, DatagramPacket request) throws IOException {

        // Extrai o nome do arquivo solicitado da mensagem GET:
        String filename = Protocol.extractFilename(message);

        if (filename.isEmpty()) {
            return;
        }

        File file = new File(filename);

        if (!file.exists()) {
            sendError(request.getAddress(), request.getPort());
            return;
        }

        System.out.println("[Servidor] GET recebido para arquivo: " + filename);

        String response = Protocol.buildOkResponse(filename); // Chama o método do protocolo para construir a resposta OK, incluindo o nome do arquivo

        byte[] data = response.getBytes(); // Converte a string de resposta em bytes para envio

        // Cria um pacote de resposta, associando os bytes da resposta ao endereço e porta do cliente (extraídos do pacote de requisição):
        DatagramPacket responsePacket = new DatagramPacket(
            data,
            data.length,
            request.getAddress(),
            request.getPort()
        );

        socket.send(responsePacket); // Envia o pacote de resposta ao cliente
        System.out.println("[Servidor] Resposta OK enviada para " + request.getAddress().getHostAddress() + ":" + request.getPort());
    }

    // Envia uma mensagem de erro para o cliente, indicando que o arquivo solicitado não foi encontrado:
    private void sendError(InetAddress addr, int port) throws IOException {
        byte[] data = Protocol.ERROR_FILE_NOT_FOUND.getBytes();
        
        DatagramPacket packet = new DatagramPacket(
            data,
            data.length,
            addr,
            port
        );

        socket.send(packet);
        System.out.println("[Servidor] Erro: Arquivo não encontrado para " + addr.getHostAddress() + ":" + port);
    }

    // ---- Métodos para o funcionamento básico do servidor ----

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
                DatagramPacket request = receivePacket(); // Bloqueia a thread até receber um pacote
                String message = extractMessage(request); // Extrai a mensagem do pacote recebido
                logReceived(message, request); // Loga detalhes do pacote recebido
                // Interpretação de comandos do protocolo:
                if (Protocol.isGet(message)) {
                    handleGet(message, request);
                } else {
                    System.out.println("[Servidor] Comando desconhecido.");
                }

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