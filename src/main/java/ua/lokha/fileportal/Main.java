package ua.lokha.fileportal;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class Main {
    private static final int packetLen = 1024 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Укажите тип server или client в командной строке");
            return;
        }

        boolean retry = true;
        switch (args[0]) {
            case "server":
                while (retry) {
                    try {
                        mainServer(args);
                        retry = false;
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.out.println("Пробуем снова через 3 сек...");
                        Thread.sleep(3000);
                    }
                }
                break;
            case "client":
                while (retry) {
                    try {
                        mainClient(args);
                        retry = false;
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.out.println("Пробуем снова через 3 сек...");
                        Thread.sleep(3000);
                    }
                }
                break;
            default:
                System.out.println("Неизвестное значение '" + args[0] + "' укажите что-то одно из: server, client");
                break;
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void mainServer(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Укажите после 'server' порт");
            return;
        }

        if (args.length < 3) {
            System.out.println("Укажите папку для передачи");
            return;
        }

        File base = new File(args[2]);
        if (!base.exists() || !base.isDirectory()) {
            System.out.println("Папка " + base + " не найдена");
            return;
        }

        System.out.println("Запускаем сервер на порту " + args[1]);
        try (ServerSocket serverSocket = new ServerSocket(Integer.parseInt(args[1]));
             Socket socket = serverSocket.accept();
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            List<File> files = new ArrayList<>();
            findFiles(base, files);

            out.writeInt(files.size());
            out.flush();

            byte[] bytes = new byte[packetLen];

            for (File file : files) {
                System.out.println("Передаем файл " + file);
                try (InputStream inFile = new FileInputStream(file)) {
                    out.writeUTF(base.getParentFile().toURI().relativize(file.toURI()).getPath()); // relative
                    out.flush();

                    long start = in.readLong();
                    if (start > 0) {
                        System.out.println("Пропускаем первые " + toLog(start));
                        inFile.skip(start);
                    }

                    long total = file.length() - start;
                    out.writeLong(total);
                    out.flush();

                    long send = 0;
                    long lastLog = 0;
                    long lastLogTime = System.currentTimeMillis();

                    long lastRead = System.currentTimeMillis();
                    int read;
                    while ((read = inFile.read(bytes, 0, Math.min(packetLen, (int)(total - send)))) != -1 && send < total) {
                        if (read == 0) {
                            if (System.currentTimeMillis() - lastRead > 10_000) {
                                throw new Exception("Уже 10 сек не идут данные");
                            }
                            continue;
                        }
                        lastRead = System.currentTimeMillis();

                        out.write(bytes, 0, read);

                        send += read;
                        if (total > 1_000_000) {
                            long current = System.currentTimeMillis();
                            long timePassed = current - lastLogTime;
                            if (timePassed > 10_000) {
                                try {
                                    System.out.println("Передача файла " + toLog(send) + "/" + toLog(total) +
                                            " " + (int)(((double)send / total) * 100) + "%" +
                                            " средняя скорость " +
                                            toLog((send - lastLog) / (timePassed / 1000)) + "/sec");
                                } catch (Throwable e) {
                                    System.out.println("BUG: " + e);
                                }
                                lastLog = send;
                                lastLogTime = current;
                            }
                        }
                    }
                }
            }

            out.flush();
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void mainClient(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Укажите после 'client' ip:порт");
        }

        System.out.println("Обращаемся к серверу по айпи " + args[1]);
        String[] ipPort = args[1].split(":");
        try (Socket socket = new Socket(ipPort[0], Integer.parseInt(ipPort[1]));
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

            int files = in.readInt();
            System.out.println("Обрабатываем " + files + " файлов.");

            byte[] bytes = new byte[packetLen];

            for (int i = 0; i < files; i++) {
                File file = new File(".", in.readUTF());
                System.out.println("Принимаем файл " + file);

                long start = 0;
                if (file.exists()) {
                    start = file.length();
                    System.out.println("Пропускаем первые " + toLog(start));
                }
                out.writeLong(start);
                out.flush();

                file.getParentFile().mkdirs();
                if (!file.exists()) {
                    file.createNewFile();
                }

                try (OutputStream outFile = new FileOutputStream(file, true)) {
                    long count = in.readLong();

                    long total = count;
                    long load = 0;
                    long lastLog = 0;
                    long lastLogTime = System.currentTimeMillis();

                    long lastRead = System.currentTimeMillis();
                    int read;
                    while ((read = in.read(bytes, 0, Math.min(packetLen, (int)count))) != -1 && count > 0) {
                        if (read == 0) {
                            if (System.currentTimeMillis() - lastRead > 10_000) {
                                throw new Exception("Уже 10 сек не идут данные");
                            }
                            continue;
                        }
                        lastRead = System.currentTimeMillis();

                        count -= read;
                        outFile.write(bytes, 0, read);

                        load += read;
                        if (total > 1_000_000) {
                            long current = System.currentTimeMillis();
                            long timePassed = current - lastLogTime;
                            if (timePassed > 10_000) {
                                try {
                                    System.out.println("Скачка файла " + toLog(load) + "/" + toLog(total) +
                                            " " + (int)(((double)load / total) * 100) + "%" +
                                            " средняя скорость " +
                                            toLog((load - lastLog) / (timePassed / 1000)) + "/sec");
                                } catch (Throwable e) {
                                    System.out.println("BUG: " + e);
                                }
                                lastLog = load;
                                lastLogTime = current;
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("ConstantConditions")
    private static void findFiles(File base, List<File> out) {
        for (File file : base.listFiles()) {
            if (file.isDirectory()) {
                findFiles(file, out);
            } else {
                out.add(file);
            }
        }
    }

    private static String toLog(long bytes) {
        if (bytes >= 1_000_000) {
            long mb = bytes / 1_000_000;
            return mb + "." + ((bytes - (mb * 1_000_000)) / 1_000) + "MB";
        }
        if (bytes >= 1_000) {
            return (bytes / 1_000) + "KB";
        }
        return bytes + "B";
    }

    /*@SneakyThrows
    private static File getCurrentJar() {
        return new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }*/
}