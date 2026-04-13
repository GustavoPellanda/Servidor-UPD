import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
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

    // ---- Métodos genéricos para envio e recepção de pacotes ----

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

    // ---- Métodos relacionados ao protocolo de comunicação ----

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

    // Recebe os pacotes de dados do servidor e rastreia os faltantes durante a recepção:
    private void receiveFile(int expectedSegments, String filename) {
        Map<Integer, byte[]> received = new TreeMap<>(); // Mapa para armazenar os segmentos recebidos, ordenado por número de sequência

        int retries = 0;
        while (retries <= Protocol.MAX_RETRIES) {

            // Loop de recepção, aguarda pacotes até receber FLAG_END ou timeout:
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
                        }
                        // Armazena o payload no mapa usando o número de sequência como chave:
                        received.put(packet.getSequenceNumber(), packet.getPayload());
                        System.out.println("[Cliente] DATA seq=" + packet.getSequenceNumber());
                    } else if (packet.isEnd()) {
                        System.out.println("[Cliente] END recebido");
                        break;
                    }

                } catch (SocketTimeoutException e) {
                    // Timeout encerra o ciclo de recepção — os faltantes serão solicitados na próxima iteração:
                    System.out.println("[Cliente] Timeout aguardando segmentos.");
                    break;
                } catch (Exception e) {
                    System.out.println("[Cliente] Erro ao receber segmento: " + e.getMessage());
                }
            }

            System.out.println("[Cliente] Recebidos: " + received.size() + "/" + expectedSegments);

            // Compara o mapa atual com o intervalo esperado de números de sequência (0 a expectedSegments-1):
            Set<Integer> missing = new HashSet<>();
            for (int i = 0; i < expectedSegments; i++) {
                if (!received.containsKey(i)) {
                    missing.add(i); // Insere o número de sequência faltante na lista de faltantes
                }
            }
            
            if (missing.isEmpty()) break; // Encerra o loop de retransmissão se nenhum faltante foi encontrado
            if (retries == Protocol.MAX_RETRIES) {  // Se ainda há faltantes mas o limite de tentativas foi atingido, abandona a transferência:
                System.out.println("[Cliente] Transferência incompleta após " + Protocol.MAX_RETRIES + " tentativas. Segmentos faltantes: " + missing);
                return;
            }

            // Solicita a retransmissão dos segmentos faltantes:
            try {
                requestRetransmission(missing);
            } catch (IOException e) {
                System.err.println("[Cliente] Erro ao solicitar retransmissão: " + e.getMessage());
            }

            retries++;
        }

        saveFile(received, filename);
    }

    // Envia um pacote NACK para o servidor com a lista dos números de sequência dos segmentos faltantes, separados por vírgula:
    private void requestRetransmission(Set<Integer> missing) throws IOException {
        if (missing.isEmpty()) return;

        // Serializa os números de sequência faltantes como uma string separada por vírgulas:
        String payload = String.join(",", missing.stream().map(String::valueOf).toArray(String[]::new));
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8); // Converte a string para bytes

        Packet nackPacket = new Packet(
            0, 
            Protocol.FLAG_NACK, 
            payloadBytes
        );
        sendPacket(nackPacket);

        System.out.println("[Cliente] NACK enviado para segmentos: " + payload);
    }

    // Simula a perda de pacotes com base na taxa de perda definida (LOSS_RATE):
    private boolean shouldDrop(int sequenceNumber) {
        if (random.nextDouble() < LOSS_RATE) {
            System.out.println("[Cliente] DESCARTADO seq=" + sequenceNumber + " (simulação de perda)");
            return true;
        }
        return false;
    }

    // Concatena os payloads do mapa em ordem de sequência e grava o arquivo reconstruído em disco:
    private void saveFile(Map<Integer, byte[]> received, String filename) {
        String outputName = "received_" + filename;
        try (FileOutputStream fos = new FileOutputStream(outputName)) {
            for (byte[] chunk : received.values()) {
                fos.write(chunk); // TreeMap garante que os chunks são escritos na ordem correta de sequência
            }
            System.out.println("[Cliente] Arquivo salvo como: " + outputName);
        } catch (IOException e) {
            System.err.println("[Cliente] Erro ao salvar arquivo: " + e.getMessage());
        }
    }

    // ---- Métodos relacionados ao controle do usuário pelo console ----

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