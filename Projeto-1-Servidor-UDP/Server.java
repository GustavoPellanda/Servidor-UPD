import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
 
import Protocol.Protocol;
import Protocol.Packet;
import Protocol.ClientSession;
 
/*
 * Implementa um servidor UDP multi-cliente baseado em sessões por cliente e thread pool,
 * no qual um único socket realiza a recepção de todos os datagramas, a demultiplexação é
 * feita pelo par IP:porta, e o processamento concorrente dos pacotes é delegado a sessões
 * independentes executadas por um pool de threads.
 */

public class Server {
 
    private static final int PORT = 9876;              // Porta onde o servidor escuta por mensagens dos clientes
    private static final int THREAD_POOL_SIZE = 10;    // Número máximo de requisições processadas em paralelo pelo pool
    private final DatagramSocket socket;               // Socket UDP usado para enviar e receber datagramas
    private boolean running;                           // Flag para controlar o loop principal do servidor

    private static final int BUFFER_SIZE = Protocol.HEADER_SIZE + Protocol.MAX_PAYLOAD; // Tamanho do buffer para receber mensagens (em bytes)
 
    // Mapa de sessões ativas, indexadas pelo identificador único do cliente ("ip:porta"):
    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
 
    // Pool de threads responsável por processar os pacotes recebidos de forma concorrente:
    private final ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
 
    public Server() throws SocketException {     // Pode lançar SocketException se a porta já estiver em uso ou houver problema de rede
        this.socket  = new DatagramSocket(PORT); // Cria um socket UDP vinculado à porta especificada
        this.running = false;                    // Inicialmente, o servidor não está rodando até que start() seja chamado
    }
 
    // Recebe um datagrama do cliente:
    private DatagramPacket receivePacket() throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE]; // Buffer para armazenar os dados recebidos
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length); // Cria um pacote para receber os dados, associando o buffer a ele
        socket.receive(packet); // Bloqueia a thread até que um pacote seja recebido, preenchendo o objeto 'packet' com os dados e informações do remetente
        return packet;
    }
 
    // Inicialização e loop principal do servidor:
    public void start() {
        running = true;
        System.out.println("[Servidor] Escutando na porta " + PORT + "...");
        System.out.println("[Servidor] Pool de threads iniciado com " + THREAD_POOL_SIZE + " threads.");
 
        while (running) {
            try {
                // Recebe o próximo datagrama UDP (bloqueante — a thread principal fica aqui até chegar um pacote):
                DatagramPacket request = receivePacket();
                byte[] raw = Arrays.copyOf(request.getData(), request.getLength()); // Copia apenas os bytes relevantes do buffer, evitando incluir bytes de padding
                Packet packet = Packet.deserialize(raw); // Interpreta os bytes recebidos como um objeto Packet, validando o checksum e extraindo os campos (seq, flags, payload)
 
                // Identifica o cliente pelo par IP:porta, formando uma chave única para o mapa de sessões:
                InetAddress addr = request.getAddress();
                int port = request.getPort();
                String clientKey = addr.getHostAddress() + ":" + port;
 
                // Busca uma sessão existente para esse cliente ou cria uma nova caso seja o primeiro contato:
                ClientSession session = sessions.computeIfAbsent( // computeIfAbsent é atômico no ConcurrentHashMap, evitando criação duplicada de sessões:
                    clientKey,
                    k -> new ClientSession(addr, port, socket)
                );
 
                // Despacha o processamento do pacote para uma thread do pool, liberando a thread principal para receber o próximo datagrama de qualquer cliente:
                pool.submit(() -> {
                    try {
                        session.handle(packet);
                    } catch (IOException e) {
                        System.err.println("[Servidor] Erro na sessão " + clientKey + ": " + e.getMessage());
                    }
                });
 
            } catch (IOException e) {
                if (running) {
                    System.err.println("[Servidor] Erro ao processar pacote: " + e.getMessage());
                }
            }
        }
    }
 
    // Encerra o servidor:
    public void stop() {
        running = false; // Interrompe o loop principal
        pool.shutdown(); // Sinaliza ao pool para não aceitar novas tarefas e aguardar as em andamento terminarem
        socket.close();  // Fecha o socket, o que fará com que receive() lance uma exceção e saia do loop
        System.out.println("[Servidor] Encerrado.");
    }
 
    public static void main(String[] args) throws SocketException {
        Server server = new Server();
        server.start();
    }
}