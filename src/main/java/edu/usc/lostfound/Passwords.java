package edu.usc.lostfound;
import java.security.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.*;

/** Salted one-way PBKDF2 for local demo authentication and verification answers. */
public final class Passwords {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final int ITERATIONS=210_000;
    private Passwords(){}
    public static String token(){byte[] b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public static String hash(String value){byte[] salt=new byte[16];RANDOM.nextBytes(salt);return "pbkdf2$"+ITERATIONS+"$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(derive(value,salt,ITERATIONS));}
    public static boolean verify(String value,String encoded){try{String[] parts=encoded.split("\\$");if(parts.length!=4||!parts[0].equals("pbkdf2"))return false;int rounds=Integer.parseInt(parts[1]);if(rounds<10000||rounds>1000000)return false;return MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]),derive(value,Base64.getDecoder().decode(parts[2]),rounds));}catch(RuntimeException e){return false;}}
    private static byte[] derive(String value,byte[] salt,int rounds){PBEKeySpec spec=new PBEKeySpec(value.toCharArray(),salt,rounds,256);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}catch(GeneralSecurityException e){throw new IllegalStateException(e);}finally{spec.clearPassword();}}
    public static String normalizedAnswer(String s){return java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip().replaceAll("\\s+"," ");}
    public static boolean valid(String s){return s.length()>=8&&s.length()<=128&&s.matches("(?s).*[A-Z].*")&&s.matches("(?s).*[0-9].*")&&!s.matches("(?s).*(.)\\1\\1.*");}
}
