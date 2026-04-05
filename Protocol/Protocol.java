package Protocol;

public class Protocol {

    public static final int MTU = 1500; // MTU padrão Ethernet (bytes)
    public static final int UDP_HEADER = 8; // Tamanho do cabeçalho UDP (bytes)
    public static final int IP_HEADER = 20; // Tamanho do cabeçalho IP (bytes)
    public static final int HEADER_SIZE = 11; // Cabeçalho do Packet: seq(4) + checksum(4) + flags(1) + dataLen(2)
    public static final int DEFAULT_PORT = 9876; // Porta padrão do servidor UDP
    public static final int MAX_PAYLOAD = MTU - UDP_HEADER - IP_HEADER - HEADER_SIZE; // Payload máximo sem fragmentação (~1461 bytes)
    public static final int CHUNK_SIZE = MAX_PAYLOAD; // Tamanho do chunk de dados (payload) em cada pacote

    public static final byte FLAG_DATA = 0x01; // Indica pacote de dados
    public static final byte FLAG_ACK = 0x02; // Indica confirmação de recebimento (uso futuro)
    public static final byte FLAG_NACK = 0x03; // Indica solicitação de retransmissão (uso futuro)
    public static final byte FLAG_ERROR = 0x04; // Indica pacote de erro
    public static final byte FLAG_END = 0x05; // Indica fim da transmissão

    public static final int TIMEOUT_MS = 3000; // Tempo limite para espera de resposta (ms) (uso futuro)
    public static final int MAX_RETRIES = 5; // Número máximo de tentativas de retransmissão (uso futuro)

    public static final String SEPARATOR = "|"; // Separador entre campos da mensagem
    public static final String CMD_GET = "GET"; // Comando de requisição de arquivo
    public static final String CMD_START = "START"; // Comando que indica início da transferência
    public static final String CMD_ERROR = "ERROR"; // Comando de erro no protocolo

    public static final String ERROR_FILE_NOT_FOUND = "ERROR|404|File not found"; // Erro quando o arquivo não existe
    public static final String ERROR_CORRUPTED = "ERROR|500|Data corrupted"; // Erro de integridade de dados

    // Constrói uma mensagem de requisição GET para um arquivo específico:
    public static String buildGetRequest(String filename) {
        return CMD_GET + SEPARATOR + filename;
    }

    // Constrói uma mensagem de resposta START com metadados do arquivo:
    public static String buildStartResponse(long fileSize, int numSegments, int chunkSize) {
        return CMD_START + SEPARATOR
                + fileSize + SEPARATOR
                + numSegments + SEPARATOR
                + chunkSize;
    }

    // Constrói uma mensagem de erro genérica no formato do protocolo:
    public static String buildError(String message) {
        return CMD_ERROR + SEPARATOR + message;
    }

    // Verifica se a mensagem recebida é um comando GET válido:
    public static boolean isGet(String message) {
        return message.startsWith(CMD_GET + SEPARATOR);
    }

    // Verifica se a mensagem recebida é um comando START válido:
    public static boolean isStart(String message) {
        return message.startsWith(CMD_START + SEPARATOR);
    }

    // Verifica se a mensagem recebida é um comando de erro:
    public static boolean isError(String message) {
        return message.startsWith(CMD_ERROR);
    }

    // Extrai o nome do arquivo solicitado a partir de uma mensagem GET:
    public static String extractFilename(String message) {
        String[] parts = parseMessage(message);
        return parts.length > 1 ? parts[1] : "";
    }

    // Converte uma mensagem textual do protocolo em seus componentes estruturais:
    // Exemplo: "START|2048|2|1024" -> ["START", "2048", "2", "1024"]
    public static String[] parseMessage(String message) {
        return message.split("\\|");
    }
}