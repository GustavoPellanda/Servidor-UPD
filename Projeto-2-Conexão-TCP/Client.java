import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Scanner;

/*
 * Cliente TCP de transferência de arquivos.
 *
 * Conecta-se ao servidor em uma porta fixa, solicita arquivos pelo nome via
 * comando GET e os salva localmente com o prefixo "received_". Após receber
 * cada arquivo, recalcula o SHA-256 localmente e compara com o hash enviado
 * pelo servidor para confirmar a integridade da transferência.
 *
 * O loop principal continua até o usuário digitar o comando de saída, momento
 * em que o cliente envia EXIT ao servidor antes de fechar a conexão.
 */

public class Client {

    private final String serverHost; // Host do servidor informado pelo usuário
    private final int    serverPort; // Porta do servidor

    private static final String EXIT_COMMAND = "sair"; // Comando que encerra o cliente

    private Socket           socket;  // Socket TCP do cliente
    private DataInputStream  in;      // Stream de leitura de dados primitivos do servidor
    private DataOutputStream out;     // Stream de escrita de dados primitivos para o servidor
    private final Scanner    scanner; // Leitura da entrada do usuário no console

    public Client() throws IOException {
        this.scanner = new Scanner(System.in);

        // Solicita endereço do servidor:
        System.out.print("[Cliente] Informe o endereço IP do servidor: ");
        this.serverHost = scanner.nextLine();

        // Solicita porta do servidor:
        System.out.print("[Cliente] Informe a porta do servidor: ");
        this.serverPort = Integer.parseInt(scanner.nextLine());
        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("Porta inválida: " + serverPort);
        }

        // Abre a conexão TCP com o servidor:
        this.socket = new Socket(serverHost, serverPort);
        this.in     = new DataInputStream(socket.getInputStream());
        this.out    = new DataOutputStream(socket.getOutputStream());
    }

    // ---- Envio de comandos ----

    // Envia o comando GET seguido do nome do arquivo:
    private void sendGet(String filename) throws IOException {
        out.writeUTF(Protocol.CMD_GET);
        out.writeUTF(filename);
        out.flush();
    }

    // Envia o comando de encerramento ao servidor:
    private void sendExit() throws IOException {
        out.writeUTF(Protocol.CMD_EXIT);
        out.flush();
    }

    // ---- Recepção e processamento da resposta ----

    // Lê a resposta do servidor após um GET e delega ao handler adequado:
    private void handleServerResponse(String filename) throws IOException {
        String response = in.readUTF(); // OK ou ERROR

        if (response.equals(Protocol.RESP_ERROR)) {
            String errorMsg = in.readUTF();
            System.out.println("[Cliente] Erro do servidor: " + errorMsg);
            return;
        }

        if (response.equals(Protocol.RESP_OK)) {
            String expectedHash = in.readUTF();  // Hash SHA-256 calculado pelo servidor
            long   fileSize     = in.readLong(); // Tamanho total do arquivo em bytes

            System.out.println("[Cliente] Servidor confirmou arquivo. Tamanho: " + fileSize + " bytes");
            System.out.println("[Cliente] Hash esperado: " + expectedHash);

            receiveFile(filename, fileSize, expectedHash);
        }
    }

    // Recebe os chunks do arquivo, monta o arquivo em disco e verifica a integridade:
    private void receiveFile(String filename, long fileSize, String expectedHash) throws IOException {
        String outputPath = "received_" + filename;

        FileOutputStream fos      = new FileOutputStream(outputPath);
        long             received = 0;
        int              chunkIndex = 0;

        // Lê chunks até encontrar o sentinela (-1 no campo de tamanho):
        while (true) {
            int chunkSize = in.readInt(); // Tamanho do próximo chunk, ou -1 se acabou

            if (chunkSize == -1) {
                System.out.println("[Cliente] Transferência concluída — todos os chunks recebidos.");
                break;
            }

            byte[] buffer = new byte[chunkSize];
            in.readFully(buffer); // Garante a leitura de exatamente chunkSize bytes, sem leituras parciais
            fos.write(buffer);

            received += chunkSize;
            System.out.println("[Cliente] chunk " + chunkIndex + " recebido (" + chunkSize + " bytes) | " + received + "/" + fileSize + " bytes");
            chunkIndex++;
        }

        fos.close();

        System.out.println("[Cliente] Arquivo salvo como: " + outputPath);

        // Verifica a integridade recalculando o SHA-256 do arquivo recebido:
        verifyIntegrity(outputPath, expectedHash);
    }

    // Calcula o SHA-256 do arquivo salvo e compara com o hash enviado pelo servidor:
    private void verifyIntegrity(String filepath, String expectedHash) throws IOException {
        String actualHash = calculateSHA256(new File(filepath));

        if (actualHash.equals(expectedHash)) {
            System.out.println("[Cliente] Integridade verificada — SHA-256 OK: " + actualHash);
        } else {
            System.out.println("[Cliente] ERRO DE INTEGRIDADE — SHA-256 não confere!");
            System.out.println("[Cliente] Esperado: " + expectedHash);
            System.out.println("[Cliente] Recebido: " + actualHash);
        }
    }

    // Calcula o hash SHA-256 do arquivo lendo-o em blocos de CHUNK_SIZE bytes,
    // evitando carregar o arquivo inteiro na memória:
    private String calculateSHA256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(Protocol.HASH_ALGORITHM);
            FileInputStream fis  = new FileInputStream(file);
            byte[] buffer        = new byte[Protocol.CHUNK_SIZE];
            int bytesRead;

            // Alimenta o digest incrementalmente, bloco a bloco:
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }

            fis.close();

            // Converte o array de bytes do digest para uma string hexadecimal legível:
            byte[]        hashBytes = digest.digest();
            StringBuilder sb        = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Algoritmo " + Protocol.HASH_ALGORITHM + " não disponível", e);
        }
    }

    // ---- Controle do console ----

    // Lê uma linha digitada pelo usuário:
    private String readUserInput() {
        System.out.print("> ");
        return scanner.nextLine().trim();
    }

    // Fecha a conexão e libera os recursos:
    private void close() {
        try {
            socket.close();
        } catch (IOException e) {
            System.err.println("[Cliente] Erro ao fechar socket: " + e.getMessage());
        }
        scanner.close();
        System.out.println("[Cliente] Encerrado.");
    }

    // Loop principal do cliente:
    public void start() {
        System.out.println("[Cliente] Conectado a " + serverHost + ":" + serverPort);
        System.out.println("[Cliente] Digite o nome do arquivo que deseja buscar (ou '" + EXIT_COMMAND + "' para encerrar):");

        while (true) {
            String input = readUserInput();

            if (input.equalsIgnoreCase(EXIT_COMMAND)) {
                try {
                    sendExit(); // Avisa o servidor antes de fechar
                } catch (IOException e) {
                    System.err.println("[Cliente] Erro ao enviar EXIT: " + e.getMessage());
                }
                break;
            }

            if (input.isEmpty()) continue;

            try {
                sendGet(input);
                handleServerResponse(input);
            } catch (IOException e) {
                System.err.println("[Cliente] Erro de I/O: " + e.getMessage());
                break; // Conexão perdida — encerra o loop
            }
        }

        close();
    }

    public static void main(String[] args) throws IOException {
        Client client = new Client();
        client.start();
    }

}
