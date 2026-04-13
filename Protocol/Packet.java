package Protocol;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

public class Packet {

    private final int sequenceNumber;
    private final int checksum;
    private final byte flags;
    private final short dataLength;
    private final byte[] payload;

    public Packet(int sequenceNumber, byte flags, byte[] payload) {
        this.sequenceNumber = sequenceNumber;
        this.flags = flags;
        this.payload = (payload != null) ? payload.clone() : new byte[0];
        this.dataLength = (short) this.payload.length;
        this.checksum = calculateChecksum();
    }

    // Construtor para pacotes sem payload (ex: ACK, NACK, END):
    public Packet(int sequenceNumber, byte flags) {
        this(sequenceNumber, flags, new byte[0]);
    }

    // Calcula o checksum do pacote usando CRC32:
    private int calculateChecksum() {
        CRC32 crc = new CRC32();

        ByteBuffer header = ByteBuffer.allocate(7);
        header.putInt(sequenceNumber);
        header.put(flags);
        header.putShort(dataLength);

        crc.update(header.array());
        crc.update(payload);

        return (int) crc.getValue();
    }

    public boolean isValid() {
        return this.checksum == calculateChecksum();
    }

    // Serializa o pacote em um array de bytes para envio:
    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(Protocol.HEADER_SIZE + payload.length);

        buffer.putInt(sequenceNumber);
        buffer.putInt(checksum);
        buffer.put(flags);
        buffer.putShort(dataLength);
        buffer.put(payload);

        return buffer.array();
    }

    // Deserializa um array de bytes recebido em um objeto Packet, validando o checksum:
    public static Packet deserialize(byte[] data) {

        if (data.length < Protocol.HEADER_SIZE) {
            throw new IllegalArgumentException("Pacote menor que o cabeçalho mínimo");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);

        int seq = buffer.getInt();
        int checksum = buffer.getInt();
        byte flags = buffer.get();
        short length = buffer.getShort();

        if (length < 0 || length > (data.length - Protocol.HEADER_SIZE)) {
            throw new IllegalArgumentException("Tamanho de payload inválido");
        }

        byte[] payload = new byte[length];
        buffer.get(payload);

        Packet packet = new Packet(seq, flags, payload);

        if (!packet.isValid()) {
            throw new IllegalArgumentException("Checksum inválido");
        }

        return packet;
    }

    // Getters para os campos do pacote:
    public int getSequenceNumber() { return sequenceNumber; }
    public byte getFlags()         { return flags; }
    public short getDataLength()   { return dataLength; }
    public byte[] getPayload()     { return payload.clone(); }
    // Métodos auxiliares para verificar o tipo do pacote com base nas flags:
    public boolean isGet()   { return flags == Protocol.FLAG_GET; }
    public boolean isStart() { return flags == Protocol.FLAG_START; }
    public boolean isData()  { return flags == Protocol.FLAG_DATA; }
    public boolean isEnd()   { return flags == Protocol.FLAG_END; }
    public boolean isError() { return flags == Protocol.FLAG_ERROR; }
    public boolean isNack()  { return flags == Protocol.FLAG_NACK; }

    @Override
    public String toString() {
        return String.format(
            "Packet[seq=%d, flag=%d, len=%d]",
            sequenceNumber, flags, dataLength
        );
    }
}