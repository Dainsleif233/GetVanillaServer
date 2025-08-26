package top.syshub;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GetServer {
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[GetServer INFO]: GetServer 运行中！");
        try {
            ClassLoader classLoader = ClassLoader.getSystemClassLoader();
            InputStream inputStream = classLoader.getResourceAsStream("META-INF/download-context");
            if (inputStream == null) throw new Error("找不到 META-INF/download-context");

            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            String[] content = reader.readLine().split("\t");
            Path cachePath = Paths.get("cache");
            Path filePath = cachePath.resolve(content[2]);
            Pattern pattern = Pattern.compile("mojang_(.*)\\.jar");
            Matcher matcher = pattern.matcher(content[2]);
            String version;
            if (matcher.find()) version = matcher.group(1);
            else throw new Error("无法解析版本号");
            String sha1 = content[0];

            System.out.println("[GetServer INFO]: 路径: " + filePath);
            System.out.println("[GetServer INFO]: 版本: " + version);
            System.out.println("[GetServer INFO]: 哈希: " + sha1);

            if (!(Files.exists(cachePath) && Files.isDirectory(cachePath)))
                Files.createDirectories(cachePath);
            if (Files.exists(filePath) && Files.isRegularFile(filePath) && calculateHash(filePath.toString()).equals(sha1)) {
                System.out.println("[GetServer INFO]: 文件已存在");
                return;
            }

            URL url = getUrl(version);
            System.out.println("[GetServer INFO]: 链接: " + url);
            downloadFile(url, filePath);
        } catch (Exception e) {
            System.err.println("[GetServer ERROR]: " + e.getMessage());
        }
    }

    private static URL getUrl(String version) throws IOException {
        URL url = new URL("https://bmclapi2.bangbang93.com/version/" + version + "/server");

        int redirectCount = 0;
        while (redirectCount < 5) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);

                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.connect();

                int responseCode = connection.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                    String location = connection.getHeaderField("Location");
                    if (location != null) {
                        url = new URL(url, location);
                        redirectCount++;
                    } else break;
                } else return url;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        throw new IOException("超过最大重定向次数: " + url);
    }

    private static String calculateHash(String filePath) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(filePath)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1)
                digest.update(buffer, 0, bytesRead);
        }
        byte[] hashBytes = digest.digest();

        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private static void downloadFile(URL url, Path filePath) throws IOException, InterruptedException {
        if (supportsRangeRequests(url)) downloadFileMultiThreaded(url, filePath);
        else downloadFileSingleThreaded(url, filePath);
    }

    private static boolean supportsRangeRequests(URL url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK)
                return false;
            
            String acceptRanges = connection.getHeaderField("Accept-Ranges");
            return "bytes".equals(acceptRanges);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void downloadFileMultiThreaded(URL url, Path filePath) throws IOException, InterruptedException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK)
                throw new IOException("下载失败，HTTP响应码: " + responseCode);
            
            long fileSize = connection.getContentLengthLong();
            if (fileSize <= 0) {
                downloadFileSingleThreaded(url, filePath);
                return;
            }
            
            System.out.println("[GetServer INFO]: 大小: " + fileSize + " 字节");
            System.out.println("[GetServer INFO]: 多线程下载中");

            long startTime = System.currentTimeMillis();
            int threadCount = Math.min(8, Math.max(2, (int) (fileSize / (8 * 1024 * 1024))));
            long chunkSize = fileSize / threadCount;

            try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
                raf.setLength(fileSize);
            }
            
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicLong totalDownloaded = new AtomicLong(0);

            for (int i = 0; i < threadCount; i++) {
                long startByte = i * chunkSize;
                long endByte = (i == threadCount - 1) ? fileSize - 1 : (i + 1) * chunkSize - 1;
                
                executor.submit(new DownloadTask(url, filePath, startByte, endByte, totalDownloaded, latch));
            }
            
            latch.await();
            executor.shutdown();
            
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            double speed = (fileSize / 1024.0 / 1024.0) / (duration / 1000.0);
            System.out.println("[GetServer INFO]: 下载完成，速度: " + String.format("%.2f", speed) + " MB/s");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void downloadFileSingleThreaded(URL url, Path filePath) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK)
                throw new IOException("下载失败，HTTP响应码: " + responseCode);

            long fileSize = connection.getContentLengthLong();
            System.out.println("[GetServer INFO]: 大小: " + fileSize + " 字节");
            System.out.println("[GetServer INFO]: 单线程下载中");

            long startTime = System.currentTimeMillis();
            try (InputStream inputStream = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(filePath.toFile())) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesRead = 0;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                }
                
                long endTime = System.currentTimeMillis();
                long duration = endTime - startTime;
                double speed = (totalBytesRead / 1024.0 / 1024.0) / (duration / 1000.0);
                System.out.println("[GetServer INFO]: 下载完成，速度: " + String.format("%.2f", speed) + " MB/s");
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static class DownloadTask implements Runnable {
        private final URL url;
        private final Path filePath;
        private final long startByte;
        private final long endByte;
        private final AtomicLong totalDownloaded;
        private final CountDownLatch latch;

        public DownloadTask(URL url, Path filePath, long startByte, long endByte, 
                           AtomicLong totalDownloaded, CountDownLatch latch) {
            this.url = url;
            this.filePath = filePath;
            this.startByte = startByte;
            this.endByte = endByte;
            this.totalDownloaded = totalDownloaded;
            this.latch = latch;
        }

        @Override
        public void run() {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("Range", "bytes=" + startByte + "-" + endByte);

                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_PARTIAL)
                    throw new IOException("分块下载失败，HTTP响应码: " + responseCode);

                try (InputStream inputStream = connection.getInputStream();
                     RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
                    
                    raf.seek(startByte);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        raf.write(buffer, 0, bytesRead);
                        totalDownloaded.addAndGet(bytesRead);
                    }
                }
            } catch (IOException e) {
                System.err.println("[GetServer ERROR]: 线程下载失败: " + e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
                latch.countDown();
            }
        }
    }
}