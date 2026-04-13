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
    public static final byte FLAG_ACK = 0x02; // Indica confirmação de recebimento
    public static final byte FLAG_NACK = 0x03; // Indica solicitação de retransmissão
    public static final byte FLAG_ERROR = 0x04; // Indica pacote de erro
    public static final byte FLAG_END = 0x05; // Indica fim da transmissão
    public static final byte FLAG_GET = 0x06; // Requisição de arquivo
    public static final byte FLAG_START = 0x07; // Resposta com metadados do arquivo

    public static final int TIMEOUT_MS = 3000; // Tempo limite para espera de resposta (ms)
    public static final int MAX_RETRIES = 5; // Número máximo de tentativas de retransmissão
}