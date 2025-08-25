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
            System.out.println("[GetServer INFO]: 下载链接: " + url);
            System.out.println("[GetServer INFO]: 下载中");
            downloadFile(url, filePath);
            System.out.println("[GetServer INFO]: 下载完成");
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
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        throw new IOException("超过最大重定向次数: " + url);
    }

    private static String calculateHash(String filePath) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(filePath)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
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

    private static void downloadFile(URL url, Path filePath) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("下载失败，HTTP响应码: " + responseCode);
            }

            long fileSize = connection.getContentLengthLong();
            System.out.println("[GetServer INFO]: 文件大小: " + fileSize + " 字节");

            try (InputStream inputStream = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(filePath.toFile())) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesRead = 0;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;

                    if (fileSize > 0) {
                        int progress = (int) ((totalBytesRead * 100) / fileSize);
                         System.out.print("\r[GetServer INFO]: 下载进度: " + progress + "%");
                    }
                }
                 System.out.println();
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}