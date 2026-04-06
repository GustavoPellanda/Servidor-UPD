import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;

import Protocol.Packet;
import Protocol.Protocol;
 
public class Client {
 
    private static final String SERVER_HOST = "localhost"; // Endereço do servidor
    private static final int SERVER_PORT = 9876;           // Porta do servidor
    private static final int TIMEOUT_MS  = 5000;           // Tempo máximo de espera pela resposta do servidor (em ms)
    private static final String EXIT_COMMAND = "sair";     // Comando que encerra o cliente
    private final DatagramSocket socket;                   // Socket UDP do cliente — porta local é atribuída automaticamente pelo SO
    private final InetAddress serverAddress;               // Endereço resolvido do servidor (hostname -> InetAddress)
    private final Scanner scanner;                         // Leitura da entrada do usuário no console
    private static final int BUFFER_SIZE = Protocol.HEADER_SIZE + Protocol.MAX_PAYLOAD; // Tamanho do buffer de recepção (em bytes)

    // Simulação de perda de pacotes (para teste do protocolo):
    private static final double LOSS_RATE = 0.2; // 20% de chance de descartar cada segmento
    private final java.util.Random random = new java.util.Random(); // Gerador de números aleatórios para simular perda de pacotes

    public Client() throws IOException {
        this.socket = new DatagramSocket(); // Cria o socket sem porta fixa, o SO escolhe uma porta disponível
        this.socket.setSoTimeout(TIMEOUT_MS); // Configura timeout (sem isso, receive() bloquearia indefinidamente se o pacote fosse perdido)
        this.serverAddress = InetAddress.getByName(SERVER_HOST); // Resolve o hostname para InetAddress uma única vez no construtor
        this.scanner = new Scanner(System.in);  // Scanner para ler a entrada do usuário a partir do console
    }

    // Envia um pacote serializado para o servidor:
    private void sendPacket(Packet packet) throws IOException {
        byte[] data = packet.serialize(); // Utiliza o método serialize() do Packet para converter o objeto em um array de bytes
        // Cria um DatagramPacket com os dados, o endereço do servidor e a porta do servidor:
        DatagramPacket udpPacket = new DatagramPacket(
            data, 
            data.length, 
            serverAddress, 
            SERVER_PORT
        );
        socket.send(udpPacket);
    }
 
    // Aguarda a resposta do servidor e a exibe no console:
    private DatagramPacket receivePacket() throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE]; // Buffer para armazenar os dados recebidos
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length); // Cria um pacote para receber os dados, associando o buffer a ele
        socket.receive(packet); // Lança SocketTimeoutException se TIMEOUT_MS expirar sem resposta
        return packet;
    }
    
    // Processa a resposta do servidor após enviar a requisição GET, verificando se é um pacote START (com metadados do arquivo) ou um pacote de erro:
    private void handleServerResponse(String filename) throws IOException {
        try {
            // Recebe um pacote do servidor e tenta deserializá-lo em um objeto Packet:
            DatagramPacket udpPacket = receivePacket();
            byte[] raw = Arrays.copyOf(udpPacket.getData(), udpPacket.getLength());
            Packet packet = Packet.deserialize(raw);

            if (packet.isStart()) {
                String meta = new String(packet.getPayload(), StandardCharsets.UTF_8);
                String[] parts = meta.split("\\|"); // Espera-se que o payload do START seja "START|numSegments"
                int numSegments = Integer.parseInt(parts[1]); // Extrai o número de segmentos que o servidor enviará, para controle de recebimento
                receiveFile(numSegments, filename); 
            } else if (packet.isError()) {
                String errorMsg = new String(packet.getPayload(), StandardCharsets.UTF_8);
                System.out.println("[Cliente] Erro do servidor: " + errorMsg);
            }

        } catch (SocketTimeoutException e) {
            System.out.printf("[Cliente] Sem resposta após %dms.%n", TIMEOUT_MS);
        }
    }

    // Recebe os pacotes de dados do servidor, armazenando-os em um mapa ordenado por número de sequência, e depois os concatena para reconstruir o arquivo completo:
    private void receiveFile(int expectedSegments, String filename) {
        Map<Integer, byte[]> received = new TreeMap<>();

        // Loop de recepção dos pacotes de dados do servidor:
        while (true) {
            try {
                // Recebe um pacote do servidor e tenta deserializá-lo em um objeto Packet:
                DatagramPacket udpPacket = receivePacket();
                byte[] raw = Arrays.copyOf(udpPacket.getData(), udpPacket.getLength());
                Packet packet = Packet.deserialize(raw);

                if (packet.isData()) {
                if (shouldDrop(packet.getSequenceNumber())) {
                    // Segmento descartado — não insere no mapa
                    continue;
                } else {
                    // Armazena o payload do pacote no mapa, usando o número de sequência como chave para garantir a ordem correta:
                    received.put(packet.getSequenceNumber(), packet.getPayload()); 
                    System.out.println("[Cliente] DATA seq=" + packet.getSequenceNumber());
                }
                } else if (packet.isEnd()) {
                    System.out.println("[Cliente] END recebido");
                    break;
                }

            } catch (Exception e) {
                System.out.println("[Cliente] Erro: " + e.getMessage());
            }
        }
        System.out.println("[Cliente] Recebidos: " + received.size() + "/" + expectedSegments);

        // Concatena os payloads em ordem (TreeMap já garante ordem por sequência):
        int totalBytes = 0;
        for (byte[] chunk : received.values()) totalBytes += chunk.length;

        // Cria um array para armazenar o conteúdo completo do arquivo, alocando o tamanho total necessário:
        byte[] fileData = new byte[totalBytes];
        int offset = 0;
        for (byte[] chunk : received.values()) {
            System.arraycopy(chunk, 0, fileData, offset, chunk.length);
            offset += chunk.length;
        }

        // Grava em disco:
        String outputName = "received_" + filename;
        try (FileOutputStream fos = new FileOutputStream(outputName)) {
            fos.write(fileData);
            System.out.println("[Cliente] Arquivo salvo como: " + outputName);
        } catch (IOException e) {
            System.err.println("[Cliente] Erro ao salvar arquivo: " + e.getMessage());
        }
    }

    // Simula a perda de pacotes com base na taxa de perda definida (LOSS_RATE):
    private boolean shouldDrop(int sequenceNumber) {
        if (random.nextDouble() < LOSS_RATE) {
            System.out.println("[Cliente] DESCARTADO seq=" + sequenceNumber + " (simulação de perda)");
            return true;
        }
        return false;
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
        System.out.println("[Cliente] Digite o nome do arquivo que deseja buscar no servidor (ou '" + EXIT_COMMAND + "' para encerrar):");

        while (true) {
            String filename = readUserInput();

            if (shouldExit(filename)) break;

            try {
                byte[] filenameBytes = filename.getBytes(StandardCharsets.UTF_8); // Converte o nome do arquivo para bytes usando UTF-8
                Packet getPacket = new Packet(0, Protocol.FLAG_GET, filenameBytes); // Cria um pacote GET com sequência 0 e o nome do arquivo como payload
                sendPacket(getPacket); // Envia o pacote GET para o servidor
                handleServerResponse(filename); // Aguarda e processa a resposta do servidor (START ou ERROR)
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