package swp391.carwash.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class VnpaySigner {
    private static final String HMAC_SHA512 = "HmacSHA512";

    public String buildPaymentUrl(String payUrl, Map<String, String> parameters, String secret) {
        String query = canonicalize(parameters);
        return payUrl + "?" + query + "&vnp_SecureHash=" + sign(query, secret);
    }

    public boolean verify(Map<String, String> parameters, String secureHash, String secret) {
        if (secureHash == null || secureHash.isBlank()) {
            return false;
        }
        String expected = sign(canonicalize(parameters), secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                secureHash.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    public String canonicalize(Map<String, String> parameters) {
        return parameters.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    public String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA512);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA512));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException ex) {
            throw new IllegalStateException("Cannot sign VNPAY request", ex);
        }
    }

    private String encode(String value) {
        // VNPAY chuẩn hóa hashData/query bằng US_ASCII. Với dữ liệu ASCII hiện tại kết quả không đổi,
        // nhưng dùng US_ASCII cho khớp spec, tránh lệch chữ ký nếu sau này có ký tự ngoài ASCII.
        return URLEncoder.encode(value, StandardCharsets.US_ASCII);
    }
}
