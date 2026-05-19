import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/*
 * Thread dedicada ao atendimento de um único cliente TCP.
 * Criada pelo servidor a cada nova conexão aceita, ela encapsula toda a lógica
 * de comunicação com aquele cliente: lê comandos, processa requisições de arquivo
 * e encerra a sessão quando o cliente envia EXIT ou a conexão cai.
 *
 * Ao terminar, remove a si mesma da lista global de handlers ativos mantida pelo servidor.
 */

public class ClientHandler implements Runnable {

    private final Socket socket;                      // Socket TCP da conexão com este cliente
    private final List<ClientHandler> activeClients;  // Referência à lista global de handlers — usada para auto-remoção ao encerrar
    private DataInputStream  in;                      // Stream de leitura de dados primitivos do cliente
    private DataOutputStream out;                     // Stream de escrita de dados primitivos para o cliente

    // Identificador textual do cliente (IP:porta), usado nos logs:
    private final String clientId;

    public ClientHandler(Socket socket, List<ClientHandler> activeClients) {
        this.socket        = socket;
        this.activeClients = activeClients;
        this.clientId      = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
    }

    @Override
    public void run() {
        System.out.println("[Servidor] Cliente conectado: " + clientId);

        try {
            // Envolve os streams do socket com DataInputStream/DataOutputStream para
            // poder usar readUTF() e writeUTF(), que enviam strings com prefixo de tamanho:
            in  = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());

            // Loop principal: lê comandos do cliente até receber EXIT ou a conexão fechar:
            while (true) {
                String command = in.readUTF(); // Bloqueia até o cliente enviar algo

                if (command.equalsIgnoreCase(Protocol.CMD_EXIT)) {
                    System.out.println("[Servidor] Cliente " + clientId + " solicitou encerramento.");
                    break;

                } else if (command.equalsIgnoreCase(Protocol.CMD_GET)) {
                    // O nome do arquivo segue logo após o comando GET:
                    String filename = in.readUTF();
                    handleGet(filename);

                } else {
                    System.out.println("[Servidor] Comando desconhecido de " + clientId + ": " + command);
                }
            }

        } catch (IOException e) {
            // Conexão encerrada abruptamente pelo cliente (ex.: processo morto):
            System.out.println("[Servidor] Conexão encerrada com " + clientId + ": " + e.getMessage());

        } finally {
            close();
        }
    }

    // Processa um comando GET: valida o caminho, calcula o hash e envia o arquivo em chunks:
    private void handleGet(String filename) throws IOException {
        System.out.println("[Servidor] " + clientId + " solicitou arquivo: " + filename);

        // ---- Validação de segurança: path traversal ----
        // Resolve o diretório raiz permitido para um caminho canônico (absoluto, sem ".." ou links simbólicos):
        File rootDir     = new File(Protocol.FILES_DIR).getCanonicalFile();
        File requestedFile = new File(rootDir, filename).getCanonicalFile();

        // Verifica se o caminho resolvido ainda está dentro do diretório raiz.
        // Sem essa checagem, um cliente poderia enviar "../../etc/passwd" e acessar arquivos arbitrários:
        if (!requestedFile.getPath().startsWith(rootDir.getPath())) {
            System.out.println("[Servidor] Tentativa de path traversal bloqueada de " + clientId + ": " + filename);
            out.writeUTF(Protocol.RESP_ERROR);
            out.writeUTF("Acesso negado: caminho inválido.");
            out.flush();
            return;
        }

        if (!requestedFile.exists() || !requestedFile.isFile()) {
            System.out.println("[Servidor] Arquivo não encontrado para " + clientId + ": " + filename);
            out.writeUTF(Protocol.RESP_ERROR);
            out.writeUTF("Arquivo não encontrado: " + filename);
            out.flush();
            return;
        }

        // ---- Envio do arquivo ----
        String hash = calculateSHA256(requestedFile); // Calcula o hash antes de enviar, para o cliente verificar a integridade

        out.writeUTF(Protocol.RESP_OK);         // Sinaliza que o arquivo foi encontrado e será enviado
        out.writeUTF(hash);                     // Envia o hash SHA-256 para verificação pelo cliente
        out.writeLong(requestedFile.length());  // Envia o tamanho total do arquivo em bytes
        out.flush();

        System.out.println("[Servidor] Enviando " + requestedFile.getName() + " (" + requestedFile.length() + " bytes) para " + clientId);

        sendFile(requestedFile);

        System.out.println("[Servidor] Transferência de " + requestedFile.getName() + " concluída para " + clientId);
    }

    // Lê o arquivo em chunks e escreve cada chunk no stream de saída:
    private void sendFile(File file) throws IOException {
        FileInputStream fis    = new FileInputStream(file);
        byte[]          buffer = new byte[Protocol.CHUNK_SIZE];
        int             bytesRead;
        int             chunkIndex = 0;

        // Lê o arquivo em pedaços de CHUNK_SIZE bytes e envia cada pedaço precedido de seu tamanho,
        // evitando carregar o arquivo inteiro na memória e permitindo arquivos de qualquer tamanho:
        while ((bytesRead = fis.read(buffer)) != -1) {
            out.writeInt(bytesRead);               // Tamanho real deste chunk (pode ser menor no último bloco)
            out.write(buffer, 0, bytesRead);       // Bytes do chunk
            out.flush();
            System.out.println("[Servidor] chunk " + chunkIndex + " enviado (" + bytesRead + " bytes) para " + clientId);
            chunkIndex++;
        }

        out.writeInt(-1); // Sentinela: indica ao cliente que o arquivo terminou
        out.flush();

        fis.close();
    }

    // Calcula o hash SHA-256 do arquivo lendo-o em blocos de CHUNK_SIZE bytes,
    // evitando sobrecarregar a memória com arquivos grandes:
    private String calculateSHA256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(Protocol.HASH_ALGORITHM);
            FileInputStream fis  = new FileInputStream(file);
            byte[] buffer        = new byte[Protocol.CHUNK_SIZE];
            int bytesRead;

            // Alimenta o digest com cada bloco lido, sem precisar ter o arquivo inteiro na RAM:
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

    // Fecha o socket e remove este handler da lista global de clientes ativos:
    private void close() {
        try {
            socket.close();
        } catch (IOException e) {
            System.err.println("[Servidor] Erro ao fechar socket de " + clientId + ": " + e.getMessage());
        }

        // Remove da lista de forma thread-safe — CopyOnWriteArrayList garante que
        // outras threads que estejam iterando a lista não serão afetadas:
        activeClients.remove(this);
        System.out.println("[Servidor] Cliente desconectado: " + clientId + " | Ativos: " + activeClients.size());
    }

}
