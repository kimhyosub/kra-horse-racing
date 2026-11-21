package com.sub.gachima.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 가이드 글에 넣을 이미지 올리기. 파일은 gachima.upload-dir(기본 C:/kra-data/uploads)/yyyy-MM/임의이름.확장자 로 저장하고
 * /uploads/yyyy-MM/… 주소로 보여준다. PNG·JPG·GIF·WEBP 만, 5MB 까지. 파일 앞부분(시그니처)으로 종류를 확인한다.
 */
@Service
public class UploadService {
    public static final long MAX = 5L * 1024 * 1024;
    private final File dir;

    public UploadService(@Value("${gachima.upload-dir:C:/kra-data/uploads}") String dir) { this.dir = new File(dir); }

    public File getDir() { return dir; }

    public String save(MultipartFile f) throws IOException {
        if (f == null || f.isEmpty()) throw new IllegalArgumentException("파일이 없습니다");
        if (f.getSize() > MAX) throw new IllegalArgumentException("5MB 이하 이미지만 올릴 수 있습니다");
        byte[] b = f.getBytes();
        String ext = kind(b);
        if (ext == null) throw new IllegalArgumentException("PNG · JPG · GIF · WEBP 이미지만 올릴 수 있습니다");
        String month = LocalDate.now().toString().substring(0, 7);
        File d = new File(dir, month);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("폴더를 만들지 못했습니다: " + d);
        String name = UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "." + ext;
        Files.write(new File(d, name).toPath(), b);
        return "/uploads/" + month + "/" + name;
    }

    static String kind(byte[] b) {
        if (b.length < 12) return null;
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8) return "jpg";
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "gif";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "webp";
        return null;
    }
}
