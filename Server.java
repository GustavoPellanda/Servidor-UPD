import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import Protocol.Protocol;
import Protocol.Packet;

public class Server {

    private static final int PORT = 9876;   // Porta onde o servidor escuta por mensagens dos clientes
    private final DatagramSocket socket;    // Socket UDP usado para enviar e receber datagramas
    private boolean running;                // Flag para controlar o loop principal do servidor
    
    private String lastFilename;   // Nome do último arquivo solicitado
    private InetAddress lastAddr;  // Endereço do cliente da última transferência
    private int lastPort;          // Porta do cliente da última transferência

    private static final int BUFFER_SIZE = Protocol.HEADER_SIZE + Protocol.MAX_PAYLOAD; // Tamanho do buffer para receber mensagens (em bytes)

    public Server() throws SocketException {     // Pode lançar SocketException se a porta já estiver em uso ou houver problema de rede
        this.socket  = new DatagramSocket(PORT); // Cria um socket UDP vinculado à porta especificada
        this.running = false;                    // Inicialmente, o servidor não está rodando até que start() seja chamado
    }

    // ---- Métodos relacionados ao protocolo de comunicação ----

    // Processa um comando GET recebido:
    private void handleGet(String filename, InetAddress addr, int port) throws IOException {
        if (filename.isEmpty()) {
            return;
        }

        File file = new File(filename);

        if (!file.exists()) {
            sendError("Arquivo não encontrado: " + filename, addr, port);
            return;
        }

        long fileSize = file.length(); // Obtém o tamanho do arquivo em bytes
        int chunkSize = Protocol.CHUNK_SIZE; // Tamanho do chunk definido no protocolo
        // Divide o tamanho do arquivo pelo tamanho do chunk para calcular o número total de segmentos necessários para transferir o arquivo completo:
        int numSegments = (int) Math.ceil((double) fileSize / chunkSize); 
        
        System.out.println("[Servidor] GET recebido para arquivo: " + filename);
        System.out.println("[Servidor] Tamanho: " + fileSize + " bytes | Segmentos: " + numSegments);

        String meta = fileSize + "|" + numSegments; // Formato do payload do START: "tamanho|numSegmentos"
        byte[] metaBytes = meta.getBytes(StandardCharsets.UTF_8); // Converte a string de metadados para bytes usando UTF-8
        
        // Cria um pacote START com sequência 0, flag de START e o payload de metadados do arquivo:
        Packet startPacket = new Packet(
            0, 
            Protocol.FLAG_START, 
            metaBytes
        ); 
        byte[] data = startPacket.serialize();
        
        // Cria um DatagramPacket com os dados do START, o endereço do cliente e a porta do cliente para envio:
        DatagramPacket udpPacket = new DatagramPacket(
            data, 
            data.length, 
            addr, 
            port
        ); 
        socket.send(udpPacket);

        // Armazena as informações da transferência para uso em retransmissões futuras (se necessário):
        this.lastFilename = filename;
        this.lastAddr = addr;
        this.lastPort = port;
        
        System.out.println("[Servidor] Enviado START para " + addr.getHostAddress() + ":" + port);
        sendFile(file, addr, port);    
    }

    // Envia o conteúdo do arquivo para o cliente, dividindo-o em chunks e enviando cada chunk como um pacote separado:
    private void sendFile(File file, InetAddress addr, int port) throws IOException {
        FileInputStream fis = new FileInputStream(file);

        byte[] buffer = new byte[Protocol.CHUNK_SIZE];
        int bytesRead;
        int seq = 0;

        // Loop de leitura do arquivo em chunks, criando um pacote para cada chunk e enviando para o cliente:
        while ((bytesRead = fis.read(buffer)) != -1) {
            byte[] chunk = Arrays.copyOf(buffer, bytesRead);
            // Cria um pacote de dados com o número de sequência atual, flag de DATA e o payload do chunk lido do arquivo:
            Packet packet = new Packet(
                seq, 
                Protocol.FLAG_DATA, 
                chunk
            );
            byte[] data = packet.serialize();

            // Cria um DatagramPacket com os dados do chunk, o endereço do cliente e a porta do cliente para envio:
            DatagramPacket udpPacket = new DatagramPacket(
                data,
                data.length,
                addr,
                port
            );
            socket.send(udpPacket);

            System.out.println("[Servidor] DATA seq=" + seq);
            seq++;
        }

        // Pacote de término com a sequência final e flag de END, sem payload:
        Packet endPacket = new Packet(seq, Protocol.FLAG_END);  
        byte[] endData = endPacket.serialize();
        DatagramPacket endUdp = new DatagramPacket(
            endData,
            endData.length,
            addr,
            port
        );
        socket.send(endUdp);

        System.out.println("[Servidor] END enviado");
        fis.close();
    }

    // Envia uma mensagem de erro para o cliente, indicando que o arquivo solicitado não foi encontrado:
    private void sendError(String message, InetAddress addr, int port) throws IOException {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        Packet errorPacket = new Packet(
            0, 
            Protocol.FLAG_ERROR, 
            msgBytes
        );
        byte[] data = errorPacket.serialize();
        DatagramPacket udpPacket = new DatagramPacket(
            data, 
            data.length, 
            addr, 
            port
        );
        socket.send(udpPacket);
    }

    // Processa um NACK recebido, retransmitindo apenas os segmentos solicitados pelo cliente:
    private void handleNack(Packet packet) throws IOException {
        if (lastFilename == null) {
            System.out.println("[Servidor] NACK recebido sem transferência ativa.");
            return;
        }

        // Extrai a lista de sequências faltantes do payload do NACK:
        String payload = new String(packet.getPayload(), StandardCharsets.UTF_8);
        String[] parts = payload.split(",");

        System.out.println("[Servidor] NACK recebido — retransmitindo segmentos: " + payload);

        File file = new File(lastFilename);
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
        byte[] buffer = new byte[Protocol.CHUNK_SIZE];

        // Para cada número de sequência faltante, calcula a posição no arquivo e retransmite o chunk:
        for (String part : parts) {
            int seq = Integer.parseInt(part.trim());
            long position = (long) seq * Protocol.CHUNK_SIZE; // Posição em bytes do início desse segmento no arquivo

            raf.seek(position); // Salta para a posição correta no arquivo
            int bytesRead = raf.read(buffer);

            if (bytesRead <= 0) continue;

            byte[] chunk = Arrays.copyOf(buffer, bytesRead);

            Packet dataPacket = new Packet(
                seq,
                Protocol.FLAG_DATA,
                chunk
            );
            byte[] data = dataPacket.serialize();

            DatagramPacket udpPacket = new DatagramPacket(
                data,
                data.length,
                lastAddr,
                lastPort
            );
            socket.send(udpPacket);

            System.out.println("[Servidor] RETRANSMITIDO seq=" + seq);
        }

        raf.close();

        // Pacote de término sinalizando o fim da retransmissão:
        Packet endPacket = new Packet(0, Protocol.FLAG_END);
        byte[] endData = endPacket.serialize();
        DatagramPacket endUdp = new DatagramPacket(
            endData,
            endData.length,
            lastAddr,
            lastPort
        );
        socket.send(endUdp);

        System.out.println("[Servidor] END enviado após retransmissão");
    }

    // ---- Métodos para o funcionamento básico do servidor ----

    // Recebe um datagrama do cliente:
    private DatagramPacket receivePacket() throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE]; // Buffer para armazenar os dados recebidos
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length); // Cria um pacote para receber os dados, associando o buffer a ele
        socket.receive(packet); // Bloqueia a thread até que um pacote seja recebido, preenchendo o objeto 'packet' com os dados e informações do remetente
        return packet;
    }

    // Inicialização e loop principal do servidor:
    public void start() {
        running = true;
        System.out.println("[Servidor] Escutando na porta " + PORT + "...");

        while (running) {
            try {
                DatagramPacket request = receivePacket(); // Aguarda um pacote de requisição do cliente (bloqueante)
                byte[] raw = Arrays.copyOf(request.getData(), request.getLength()); // Copia apenas os bytes relevantes do buffer, evitando incluir bytes de padding
                Packet packet = Packet.deserialize(raw); // Interpreta os bytes recebidos como um objeto Packet, validando o checksum e extraindo os campos (seq, flags, payload)

                // Interpretação de comandos do protocolo:
                if (packet.isGet()) {
                    String filename = new String(packet.getPayload(), StandardCharsets.UTF_8); // Converte o payload do pacote GET de volta para uma string
                    handleGet(filename, request.getAddress(), request.getPort());
                } else if (packet.isNack()) {
                    handleNack(packet);
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