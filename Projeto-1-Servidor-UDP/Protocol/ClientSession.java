package Protocol;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Classe que representa uma sessão ativa com um cliente específico.
 * Encapsula todo o estado e a lógica de comunicação referente a um único cliente,
 * identificado pelo par (endereço IP, porta). Dessa forma, múltiplas sessões podem
 * existir simultaneamente sem que seus estados se misturem.
 */

public class ClientSession {

    private final InetAddress addr;      // Endereço IP do cliente desta sessão
    private final int port;              // Porta UDP do cliente desta sessão
    private final DatagramSocket socket; // Socket compartilhado do servidor — usado para enviar respostas a este cliente
    private String lastFilename;         // Nome do último arquivo solicitado por este cliente (usado em retransmissões via NACK)
    private int lastNumSegments;         // Número de segmentos da última transferência, para calcular o tamanho total do bitmap
    private byte[] nackBitmapBuffer;     // Buffer de acumulação dos fragmentos do bitmap de NACK

    public ClientSession(InetAddress addr, int port, DatagramSocket socket) {
        this.addr   = addr;
        this.port   = port;
        this.socket = socket;
    }

    // Método público de entrada, chamado pelo dispatcher do servidor:
    public synchronized void handle(Packet packet) throws IOException {
        // Recebe um packet já deserializado e decide qual handler invocar com base na flag do pacote:
        if (packet.isGet()) {
            String filename = new String(packet.getPayload(), StandardCharsets.UTF_8); // Converte o payload do pacote GET de volta para uma string
            handleGet(filename);
        } else if (packet.isNack()) {
            handleNack(packet);
        } else {
            System.out.println("[Sessão " + addr.getHostAddress() + ":" + port + "] Comando desconhecido.");
        }
    } // *Declarado como synchronized para garantir que apenas uma thread do pool processe esta sessão por vez, evitando condições de corrida no estado interno (lastFilename)

    // Processa um comando GET recebido:
    private void handleGet(String filename) throws IOException {
        if (filename.isEmpty()) {
            return;
        }

        File file = new File(filename);

        if (!file.exists()) {
            sendError("Arquivo não encontrado: " + filename);
            return;
        }

        long fileSize = file.length(); // Obtém o tamanho do arquivo em bytes
        int chunkSize = Protocol.CHUNK_SIZE; // Tamanho do chunk definido no protocolo
        // Divide o tamanho do arquivo pelo tamanho do chunk para calcular o número total de segmentos necessários para transferir o arquivo completo:
        int numSegments = (int) Math.ceil((double) fileSize / chunkSize);

        System.out.println("[Sessão " + addr.getHostAddress() + ":" + port + "] GET recebido para arquivo: " + filename);
        System.out.println("[Sessão " + addr.getHostAddress() + ":" + port + "] Tamanho: " + fileSize + " bytes | Segmentos: " + numSegments);

        String md5 = calculateMD5(file); // Calcula o hash MD5 do arquivo completo para verificação de integridade pelo cliente
        String meta = fileSize + "|" + numSegments + "|" + md5; // Formato do payload do START: "tamanho|numSegmentos|md5"
        byte[] metaBytes = meta.getBytes(StandardCharsets.UTF_8); // Converte a string de metadados para bytes usando UTF-8

        // Cria um pacote START com sequência 0, flag de START e o payload de metadados do arquivo:
        Packet startPacket = new Packet(
            0,
            Protocol.FLAG_START,
            metaBytes
        );
        byte[] data = startPacket.serialize();
        // Transsforma o pacote START em um datagrama UDP e envia para o cliente usando o socket compartilhado do servidor:
        DatagramPacket udpPacket = new DatagramPacket(data, data.length, addr, port);
        socket.send(udpPacket);

        try {
            sendFile(file);

            System.out.println("[Sessão " + addr.getHostAddress() + ":" + port + "] Transferência concluída com sucesso.");
            this.lastFilename = filename; // Armazena o nome do arquivo para uso em retransmissões futuras solicitadas via NACK
            this.lastNumSegments = numSegments;
            this.nackBitmapBuffer = new byte[(int) Math.ceil(numSegments / 8.0)]; // Prepara o buffer de bitmap para acumular os NACKs futuros

        } catch (IOException e) {
            System.err.println("[Sessão " + addr.getHostAddress() + ":" + port + "] Falha durante envio: " + e.getMessage());
        }
    }

    // Envia o conteúdo do arquivo para o cliente, dividindo-o em chunks e enviando cada chunk como um pacote separado:
    private void sendFile(File file) throws IOException {
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
            DatagramPacket udpPacket = new DatagramPacket(data, data.length, addr, port);
            socket.send(udpPacket);

            System.out.println("[Sessão " + addr.getHostAddress() + ":" + port + "] DATA seq=" + seq);
            seq++;
        }

        // Cria um pacote de término com a sequência final e flag de END, sem payload:
        Packet endPacket = new Packet(
            seq, 
            Protocol.FLAG_END
        );
        byte[] endData = endPacket.serialize();
        DatagramPacket endUdp = new DatagramPacket(endData, endData.length, addr, port);
        socket.send(endUdp);

        System.out.println("[Sessão " + addr.getHostAddress() + ":" + port + "] END enviado");
        fis.close();
    }

    // Envia uma mensagem de erro para o cliente, indicando que o arquivo solicitado não foi encontrado:
    private void sendError(String message) throws IOException {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        Packet errorPacket = new Packet(
            0,
            Protocol.FLAG_ERROR,
            msgBytes
        );
        byte[] data = errorPacket.serialize();
        DatagramPacket udpPacket = new DatagramPacket(data, data.length, addr, port);
        socket.send(udpPacket);
    }

    // Processa um NACK recebido, retransmitindo apenas os segmentos solicitados pelo cliente:
    private void handleNack(Packet packet) throws IOException {
        if (lastFilename == null) {
            System.out.println("[Sessão " + addr.getHostAddress() + ":" + port + "] NACK recebido sem transferência ativa.");
            return;
        }

        // Copia o fragmento recebido para a posição correta do buffer acumulador — o offset em bytes dentro do bitmap completo é carregado no sequenceNumber do pacote:
        int offset = packet.getSequenceNumber();
        byte[] fragment = packet.getPayload();
        System.arraycopy(fragment, 0, nackBitmapBuffer, offset, fragment.length);

        // Verifica se o bitmap está completo comparando o fim deste fragmento com o tamanho total do buffer. Enquanto incompleto, retorna sem retransmitir nem enviar END:
        boolean isLastFragment = (offset + fragment.length == nackBitmapBuffer.length);
        if (!isLastFragment) {
            System.out.println("[Sessão " + addr.getHostAddress() + ":" + port + "] Fragmento NACK recebido (offset=" + offset + ", " + fragment.length + " bytes) — aguardando restantes");
            return;
        }

        System.out.println("[Sessão " + addr.getHostAddress() + ":" + port + "] Bitmap NACK completo — processando retransmissão");

        File file = new File(lastFilename); // Reabre o arquivo para leitura
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r"); // Usa RandomAccessFile para permitir saltos aleatórios no arquivo
        byte[] buffer = new byte[Protocol.CHUNK_SIZE]; // Buffer para ler os chunks do arquivo durante a retransmissão

        // Itera sobre cada byte do bitmap e, dentro de cada byte, sobre cada bit:
        for (int byteIndex = 0; byteIndex < nackBitmapBuffer.length; byteIndex++) {
            for (int bitIndex = 0; bitIndex < 8; bitIndex++) {
                if ((nackBitmapBuffer[byteIndex] & (1 << bitIndex)) != 0) { // Se o bit estiver ativo (valor 1), o segmento está faltando e deve ser retransmitido 
                    int seq = byteIndex * 8 + bitIndex; // O bit na posição (byteIndex * 8 + bitIndex) corresponde ao segmento de mesmo número de sequência

                    if (seq >= lastNumSegments) continue; // Ignora bits de padding além do último segmento real

                    long position = (long) seq * Protocol.CHUNK_SIZE; // Posição em bytes do início desse segmento no arquivo
                    raf.seek(position); // Salta para a posição correta no arquivo
                    int bytesRead = raf.read(buffer); // Lê o chunk do arquivo correspondente a esse segmento
                    if (bytesRead <= 0) continue;

                    // Cria um pacote de dados para o segmento faltante, usando o número de sequência correspondente e o chunk lido do arquivo:
                    byte[] chunk = Arrays.copyOf(buffer, bytesRead);
                    Packet dataPacket = new Packet(
                        seq,
                        Protocol.FLAG_DATA,
                        chunk
                    );
                    byte[] data = dataPacket.serialize();
                    DatagramPacket udpPacket = new DatagramPacket(data, data.length, addr, port);
                    socket.send(udpPacket);

                    System.out.println("[Sessão " + addr.getHostAddress() + ":" + port + "] RETRANSMITIDO seq=" + seq);
                }
            }
        }
        raf.close();

        // Reinicia o buffer para a próxima rodada de NACK — caso o cliente precise solicitar retransmissão novamente após receber os segmentos desta rodada:
        nackBitmapBuffer = new byte[nackBitmapBuffer.length];

        // Cria um pacote de término sinalizando o fim da retransmissão:
        Packet endPacket = new Packet(
            0, 
            Protocol.FLAG_END
        );
        byte[] endData = endPacket.serialize();
        DatagramPacket endUdp = new DatagramPacket(endData, endData.length, addr, port);
        socket.send(endUdp);

        System.out.println("[Sessão " + addr.getHostAddress() + ":" + port + "] END enviado após retransmissão");
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
}
