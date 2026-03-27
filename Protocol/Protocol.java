package Protocol;

public class Protocol {
    public static final int DEFAULT_PORT = 9876; // Porta padrão para o servidor
    public static final int CHUNK_SIZE = 1024; // Tamanho do chunk para transferência (1KB)
    public static final String CMD_GET = "GET"; // Comando para solicitar um arquivo do servidor
    public static final String CMD_START = "START"; // Comando para iniciar a transferência de um arquivo
    public static final String SEPARATOR = "|"; // Separador usado para delimitar partes da mensagem (comando, nome do arquivo, etc.)
    public static final String RESPONSE_OK = "OK"; // Mensagem de resposta quando a operação é bem-sucedida
    public static final String ERROR_FILE_NOT_FOUND = "ERRO: Arquivo nao encontrado"; // Mensagem de erro quando o arquivo solicitado não existe no servidor

    // Constrói uma mensagem de requisição GET para um arquivo específico:
    public static String buildGetRequest(String filename) {
        return CMD_GET + SEPARATOR + filename;
    }

    // Verifica se a mensagem recebida é um comando GET válido:
    public static boolean isGet(String message) {
        return message.startsWith(CMD_GET + SEPARATOR);
    }

    // Extrai o nome do arquivo solicitado de uma mensagem GET:
    public static String extractFilename(String message) {
        String[] parts = message.split("\\|");
        return parts.length > 1 ? parts[1] : "";
    }

    // Constrói uma mensagem de resposta OK, incluindo o nome do arquivo:
    public static String buildOkResponse(String filename) {
        return RESPONSE_OK + SEPARATOR + filename;
    }

    // Verifica se a mensagem recebida é um comando START válido:
    public static boolean isStart(String message) {
        return message.startsWith(CMD_START + SEPARATOR);
    }

    // Constrói uma mensagem de resposta START, incluindo o tamanho do arquivo, número de segmentos e tamanho do chunk:
    public static String buildStartResponse(long fileSize, int numSegments, int chunkSize) {
        return CMD_START + SEPARATOR + fileSize + SEPARATOR + numSegments + SEPARATOR + chunkSize;
    } 

    // Converte uma mensagem textual do protocolo em seus componentes estruturais:
    // Exemplo: "START|2048|2|1024" -> ["START", "2048", "2", "1024"]
    public static String[] parseMessage(String message) {
        return message.split("\\|");
    }
}