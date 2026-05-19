/*
 * Define as constantes e utilitários compartilhados entre servidor e cliente.
 * Centraliza o protocolo de comunicação: comandos de texto trocados pelo DataOutputStream/DataInputStream
 * e parâmetros de transferência como tamanho de chunk e algoritmo de hash.
 */

public class Protocol {

    // Porta fixa onde o servidor aguarda conexões:
    public static final int PORT = 9876;

    // Tamanho dos chunks usados para ler e enviar arquivos em pedaços (8 KB):
    public static final int CHUNK_SIZE = 8 * 1024;

    // Algoritmo de hash utilizado para verificação de integridade:
    public static final String HASH_ALGORITHM = "SHA-256";

    // Diretório raiz de onde o servidor serve arquivos — apenas arquivos dentro dele são permitidos:
    public static final String FILES_DIR = "./arquivos";

    // Comandos do protocolo — enviados como strings pelo cliente via DataOutputStream:
    public static final String CMD_GET  = "GET";   // Solicita um arquivo ao servidor
    public static final String CMD_EXIT = "EXIT";  // Encerra a conexão com o servidor

    // Respostas do protocolo — enviadas como strings pelo servidor via DataOutputStream:
    public static final String RESP_OK    = "OK";    // Arquivo encontrado; em seguida vêm hash + bytes
    public static final String RESP_ERROR = "ERROR"; // Arquivo não encontrado ou caminho inválido

}
