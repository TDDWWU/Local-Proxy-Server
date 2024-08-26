import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

public class LocalProxyServer {
    private static final int PORT = 8080;
    private static final int THREAD_POOL_SIZE = 100; // 可以根据需要调整线程池的大小

    public static void main(String[] args) {
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("代理服务器正在监听: " + PORT + " 端口");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                // System.out.println("接收客户端的连接");
                executorService.submit(new Handler(clientSocket, cache));
            }
        } catch (IOException e) {
            System.err.println("代理服务器错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            executorService.shutdown(); // 关闭线程池
        }
    }

}

class Handler extends Thread {
    ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();
    Socket clientSocket;

    public Handler(Socket cliSocket, ConcurrentHashMap<String, byte[]> cache) {
        this.clientSocket = cliSocket;
        this.cache = cache;
    }

    @Override
    public void run() {
        try {

            InputStream clientInput = clientSocket.getInputStream();
            OutputStream clientOutput = clientSocket.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientInput));

            String requestLine = reader.readLine();
            if (requestLine != null && !requestLine.isEmpty()) {
                String[] requestParts = requestLine.split(" ");
                if (requestParts.length < 3)
                    return;

                String method = requestParts[0];
                String url = requestParts[1];

                if ("CONNECT".equalsIgnoreCase(method)) {
                    // 分割 target 以获取主机名和端口号
                    String[] targetParts = url.split(":");
                    String targetHost = targetParts[0];
                    int targetPort = Integer.parseInt(targetParts[1]);


                    String headerLine;
                    String headers = ""; // 用于存储所有头信息
                    String host = "";
                    while (!(headerLine = reader.readLine()).isEmpty()) {
                        headers += headerLine + "\r\n"; // 将每个头信息附加到headers变量

                        if (headerLine.toLowerCase().startsWith("host:")) {
                            host = headerLine.split(": ")[1]; // 提取Host头信息
                        }
                    }
                    /*             */
                    // 处理HTTPS请求
                    handleHttpsRequest(targetHost, targetPort, clientSocket);
                } else {
                    // 读取并转发请求头
                    String headerLine;
                    String headers = ""; // 用于存储所有头信息
                    String host = "";
                    while (!(headerLine = reader.readLine()).isEmpty()) {
                        headers += headerLine + "\r\n"; // 将每个头信息附加到headers变量

                        if (headerLine.toLowerCase().startsWith("host:")) {
                            host = headerLine.split(": ")[1]; // 提取Host头信息
                        }
                    }

                    URL targetUrl;
                    if (!host.isEmpty() && !url.startsWith("http")) {
                        targetUrl = new URL("http://" + host + url);
                    } else {
                        targetUrl = new URL(url);
                    }

                    byte[] cachedData = cache.get(url);
                    if (cachedData != null) {

                        // 缓存中存在数据，直接发送给客户端
                        clientOutput.write(cachedData);
                        clientOutput.flush();
                    } else {
                        String targetHost = targetUrl.getHost();
                        int targetPort = targetUrl.getPort() != -1 ? targetUrl.getPort() : 80;

                        // 创建到目标服务器的Socket连接
                        try (Socket serverSocket = new Socket(targetHost, targetPort);
                             InputStream serverInput = serverSocket.getInputStream();
                             OutputStream serverOutput = serverSocket.getOutputStream();
                             ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream()) {

                            // 将客户端的原始请求转发到服务器
                            serverOutput.write((requestLine + "\r\n" + headers + "\r\n").getBytes());
                            // 将服务器响应转发回客户端
                            byte[] buffer = new byte[2042];
                            int bytesRead;
                            try {
                                serverSocket.setSoTimeout(1000);
                                while ((bytesRead = serverInput.read(buffer)) != -1) {
                                    clientOutput.write(buffer, 0, bytesRead);
                                    responseBuffer.write(buffer, 0, bytesRead);
                                    clientOutput.flush();
                                }

                            } catch (SocketTimeoutException e) {
                                responseBuffer.flush();
                                byte[] responseData = responseBuffer.toByteArray();
                                cache.put(targetUrl.toString(), responseData);
                            }

                            /* 处理后续http请求，请求同一个host的 */
                            while (true) {
                                StringBuilder builder = new StringBuilder();
                                requestLine = reader.readLine();
                                if (requestLine != null) {
                                    // System.out.println(requestLine);
                                    String urlString = requestLine.split(" ")[1];
                                    builder.append(requestLine + "\r\n");
                                    while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                                        // System.out.println(headerLine);
                                        builder.append(headerLine + "\r\n");
                                    }
                                    builder.append("\r\n");

                                    if (cache.containsKey(urlString)) {
                                        clientOutput.write(cache.get(urlString));
                                        clientOutput.flush();
                                        System.out.println("cached-------------------!!!");
                                    } else {
                                        serverOutput.write(builder.toString().getBytes());
                                        serverOutput.flush();
                                        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
                                        try {
                                            serverSocket.setSoTimeout(1000);

                                            while ((bytesRead = serverInput.read(buffer)) != -1) {
                                                clientOutput.write(buffer, 0, bytesRead);
                                                outBuffer.write(buffer, 0, bytesRead);
                                                clientOutput.flush();

                                            }
                                        } catch (SocketTimeoutException e) {
                                            System.out.println("Time out");
                                            outBuffer.flush();
                                            byte[] ResponseData = outBuffer.toByteArray();
                                            cache.put(urlString, ResponseData);

                                        }

                                    }
                                    try {
                                        Thread.sleep(100);
                                    } catch (Exception e) {

                                    }
                                }

                            }
                        } catch (SocketException e) {
                            System.out.println("exit");
                            clientOutput.flush();
                        }
                    }

                }

            }

        } catch (SocketTimeoutException e) {
            System.err.println("客户端请求超时: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("处理客户端请求错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    private void handleHttpsRequest(String targetHost, int targetPort, Socket clientSocket) {
        try (Socket serverSocket = new Socket(targetHost, targetPort);
             InputStream clientInput = clientSocket.getInputStream();
             OutputStream clientOutput = clientSocket.getOutputStream();
             InputStream serverInput = serverSocket.getInputStream();
             OutputStream serverOutput = serverSocket.getOutputStream()) {

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientOutput));
            writer.write("HTTP/1.1 200 Connection Established\r\n\r\n");
            writer.flush();
            // System.out.println("你好，------------------");
            Thread clientToServerThread = new Thread(() -> forwardData(clientInput, serverOutput));
            Thread serverToClientThread = new Thread(() -> forwardData(serverInput, clientOutput));

            clientToServerThread.start();
            serverToClientThread.start();

            clientToServerThread.join();
            serverToClientThread.join();

        } catch (IOException | InterruptedException e) {
            System.err.println("Error handling HTTPS request: " + e.getMessage());
            e.printStackTrace();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void forwardData(InputStream input, OutputStream output) {
        try {
            byte[] buffer = new byte[4096];
            int bytesRead;
            int timeout = 10000; // 设置超时时间为10秒
            long lastReadTime = System.currentTimeMillis();

            while (true) {
                if (input.available() > 0) {
                    bytesRead = input.read(buffer);
                    if (bytesRead == -1)
                        break; // 流结束

                    output.write(buffer, 0, bytesRead);
                    output.flush();
                    lastReadTime = System.currentTimeMillis();
                } else {
                    // 检查是否超时
                    if ((System.currentTimeMillis() - lastReadTime) > timeout) {
                        throw new SocketTimeoutException("Timeout in forwardData");
                    }
                    try {
                        Thread.sleep(100); // 短暂休眠以避免密集循环
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); // 恢复中断状态
                        break;
                    }
                }
            }
        } catch (SocketTimeoutException e) {
            // System.out.println("Connection timed out, closing connection.");
        } catch (IOException e) {
            // System.out.println("IOException, closing connection.");
        } finally {
            try {
                input.close();
                output.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
