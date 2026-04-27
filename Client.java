import java.io.File;
import java.io.FileInputStream;
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

    private final String serverHost;     // Host definido pelo usuário
    private final int serverPort;        // Porta definida pelo usuário
    private final double lossRate;       // Taxa de perda definida pelo usuário

    private static final int TIMEOUT_MS  = 5000;           // Tempo máximo de espera pela resposta do servidor (em ms)
    private static final String EXIT_COMMAND = "sair";     // Comando que encerra o cliente
    private final DatagramSocket socket;                   // Socket UDP do cliente — porta local é atribuída automaticamente pelo SO
    private final InetAddress serverAddress;               // Endereço resolvido do servidor (hostname -> InetAddress)
    private final Scanner scanner;                         // Leitura da entrada do usuário no console
    
    private static final int BUFFER_SIZE = Protocol.HEADER_SIZE + Protocol.MAX_PAYLOAD; // Tamanho do buffer de recepção (em bytes)
    private final java.util.Random random = new java.util.Random(); // Gerador de números aleatórios para simular perda de pacotes

    public Client() throws IOException {
        this.scanner = new Scanner(System.in);

        // Solicita IP do servidor:
        System.out.print("[Cliente] Informe o endereço IP do servidor: ");
        this.serverHost = scanner.nextLine();

        // Solicita porta do servidor:
        System.out.print("[Cliente] Informe a porta do servidor: ");
        this.serverPort = Integer.parseInt(scanner.nextLine());
        if (serverPort < 1024 || serverPort > 65535) {
            throw new IllegalArgumentException("Porta inválida.");
        }

        // Solicita taxa de perda:
        System.out.print("[Cliente] Informe a taxa de perda (0.0 a 1.0): ");
        this.lossRate = Double.parseDouble(scanner.nextLine());
        if (lossRate < 0.0 || lossRate > 1.0) {
            throw new IllegalArgumentException("Taxa de perda inválida.");
        }

        // Cria o socket UDP do cliente e define o timeout para recepção de pacotes:
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(TIMEOUT_MS);

        // Resolve o hostname do servidor para um endereço IP:
        this.serverAddress = InetAddress.getByName(serverHost);
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
            serverPort 
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
                String expectedMD5 = parts[2]; // Hash do arquivo completo enviado pelo servidor
                receiveFile(numSegments, filename, expectedMD5); 
            } else if (packet.isError()) {
                String errorMsg = new String(packet.getPayload(), StandardCharsets.UTF_8);
                System.out.println("[Cliente] Erro do servidor: " + errorMsg);
            }

        } catch (SocketTimeoutException e) {
            System.out.printf("[Cliente] Sem resposta após %dms.%n", TIMEOUT_MS);
        }
    }

    // Recebe os pacotes de dados do servidor e rastreia os faltantes durante a recepção:
    private void receiveFile(int expectedSegments, String filename, String expectedMD5) {
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
                requestRetransmission(missing, expectedSegments);
            } catch (IOException e) {
                System.err.println("[Cliente] Erro ao solicitar retransmissão: " + e.getMessage());
            }

            retries++;
        }

        saveFile(received, filename, expectedMD5);
    }

    // Envia um pacote NACK para o servidor solicitando a retransmissão dos segmentos faltantes, utilizando um bitmap para indicar quais segmentos precisam ser reenviados:
    private void requestRetransmission(Set<Integer> missing, int numSegments) throws IOException {
        if (missing.isEmpty()) return;

        // Calcula o tamanho do bitmap necessário para representar os segmentos faltantes, considerando que cada byte pode representar 8 segmentos:
        byte[] bitmap = new byte[(int) Math.ceil(numSegments / 8.0)];

        // Preenche o bitmap com 1 nas posições correspondentes aos segmentos faltantes e 0 nas posições dos segmentos recebidos:
        for (int seq : missing) {
            bitmap[seq / 8] |= (byte) (1 << (seq % 8));
        }

        // Cria um pacote NACK com o bitmap como payload:
        Packet nackPacket = new Packet(
            0,
            Protocol.FLAG_NACK,
            bitmap
        );
        sendPacket(nackPacket);

        System.out.println("[Cliente] NACK enviado — " + missing.size() + " segmentos faltantes (bitmap de " + bitmap.length + " bytes)");
    }

    // Simula a perda de pacotes com base na taxa de perda definida (LOSS_RATE):
    private boolean shouldDrop(int sequenceNumber) {
        if (random.nextDouble() < lossRate) {
            System.out.println("[Cliente] DESCARTADO seq=" + sequenceNumber + " (simulação de perda)");
            return true;
        }
        return false;
    }

    // Concatena os payloads do mapa em ordem de sequência e grava o arquivo reconstruído em disco:
    private void saveFile(Map<Integer, byte[]> received, String filename, String expectedMD5) {
        String outputName = "received_" + filename;
        try (FileOutputStream fos = new FileOutputStream(outputName)) {
            for (byte[] chunk : received.values()) {
                fos.write(chunk); // TreeMap garante que os chunks são escritos na ordem correta de sequência
            }
            System.out.println("[Cliente] Arquivo salvo como: " + outputName);
        } catch (IOException e) {
            System.err.println("[Cliente] Erro ao salvar arquivo: " + e.getMessage());
        }

        // Verifica a integridade do arquivo salvo comparando o MD5 com o hash recebido no START:
        try {
            String actualMD5 = calculateMD5(new java.io.File(outputName));
            if (actualMD5.equals(expectedMD5)) {
                System.out.println("[Cliente] Integridade verificada — MD5 OK: " + actualMD5);
            } else {
                System.out.println("[Cliente] ERRO DE INTEGRIDADE — MD5 não confere!");
                System.out.println("[Cliente] Esperado: " + expectedMD5);
                System.out.println("[Cliente] Recebido: " + actualMD5);
            }
        } catch (IOException e) {
            System.err.println("[Cliente] Erro ao verificar integridade: " + e.getMessage());
        }

    }

    // Método auxiliar para calcular o checksum MD5 de um arquivo, usado para validar a integridade dos dados transferidos:
    private String calculateMD5(File file) throws IOException {
        try {
            // Usa a classe MessageDigest para calcular o hash MD5 do arquivo, lendo-o em chunks para evitar sobrecarregar a memória:
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[Protocol.CHUNK_SIZE];
            int bytesRead;

            // Lê o arquivo em chunks, atualizando o digest com cada chunk lido:
            while ((bytesRead = fis.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }

            fis.close();

            // Converte o array de bytes do digest para uma string hexadecimal:
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString(); // Retorna a string hexadecimal do hash MD5 do arquivo

        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("Algoritmo MD5 não disponível", e);
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
        System.out.println("[Cliente] Conectado a " + serverHost + ":" + serverPort);
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