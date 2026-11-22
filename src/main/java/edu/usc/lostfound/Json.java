package edu.usc.lostfound;

import java.util.*;
import java.lang.reflect.Array;

/** Small strict JSON codec. Never uses reflection over domain objects or their secrets. */
public final class Json {
    private Json() {}
    public static String write(Object value) {
        if(value==null) return "null";
        if(value instanceof String || value instanceof Character || value instanceof java.time.temporal.TemporalAccessor || value instanceof java.util.Date) return quote(value.toString());
        if(value instanceof Boolean) return value.toString();
        if(value instanceof Number n) { if(!Double.isFinite(n.doubleValue())) return "null"; return n.toString(); }
        if(value instanceof Map<?,?> m) { var j=new StringJoiner(",","{","}"); m.forEach((k,v)->j.add(quote(k.toString())+":"+write(v))); return j.toString(); }
        if(value instanceof Iterable<?> list) {var j=new StringJoiner(",","[","]"); for(var v:list)j.add(write(v));return j.toString();}
        if(value.getClass().isArray()) {var j=new StringJoiner(",","[","]");for(int i=0;i<Array.getLength(value);i++)j.add(write(Array.get(value,i)));return j.toString();}
        throw new IllegalArgumentException("Unsupported JSON value");
    }
    private static String quote(String s) {var b=new StringBuilder("\"");for(char c:s.toCharArray())switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}return b.append('"').toString();}
    public static Map<String,Object> object(String s) {Object o=new Parser(s).parse();if(!(o instanceof Map))throw new IllegalArgumentException("Expected a JSON object");@SuppressWarnings("unchecked") Map<String,Object> m=(Map<String,Object>)o;return m;}
    private static final class Parser {
        final String s;int p=0,depth=0;Parser(String s){this.s=s;}
        RuntimeException bad(){return new IllegalArgumentException("Invalid JSON");}
        void ws(){while(p<s.length()&&" \r\n\t".indexOf(s.charAt(p))>=0)p++;}
        Object parse(){Object v=value();ws();if(p!=s.length())throw bad();return v;}
        Object value(){ws();if(++depth>30||p>=s.length())throw bad();char c=s.charAt(p);Object v;
            if(c=='{'){p++;var m=new LinkedHashMap<String,Object>();ws();if(take('}'))v=m;else{do{ws();if(p>=s.length()||s.charAt(p)!='"')throw bad();String k=str();ws();if(!take(':'))throw bad();Object val=value();if(m.containsKey(k))throw bad();m.put(k,val);ws();if(take('}'))break;if(!take(','))throw bad();}while(true);v=m;}}
            else if(c=='['){p++;var a=new ArrayList<Object>();ws();if(take(']'))v=a;else{do{a.add(value());ws();if(take(']'))break;if(!take(','))throw bad();}while(true);v=a;}}
            else if(c=='"')v=str();else if(s.startsWith("true",p)){p+=4;v=true;}else if(s.startsWith("false",p)){p+=5;v=false;}else if(s.startsWith("null",p)){p+=4;v=null;}else{int start=p;while(p<s.length()&&"0123456789.eE+-".indexOf(s.charAt(p))>=0)p++;if(p==start)throw bad();String n=s.substring(start,p);if(!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw bad();try{if(n.contains(".")||n.contains("e")||n.contains("E")){double decimal=Double.parseDouble(n);if(!Double.isFinite(decimal))throw bad();v=Double.valueOf(decimal);}else v=Long.valueOf(n);}catch(NumberFormatException e){throw bad();}}
            depth--;return v;}
        boolean take(char c){if(p<s.length()&&s.charAt(p)==c){p++;return true;}return false;}
        String str(){if(!take('"'))throw bad();var b=new StringBuilder();while(p<s.length()){char c=s.charAt(p++);if(c=='"')return b.toString();if(c<32)throw bad();if(c=='\\'){if(p>=s.length())throw bad();char e=s.charAt(p++);switch(e){case '"','\\','/'->b.append(e);case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{if(p+4>s.length())throw bad();try{b.append((char)Integer.parseInt(s.substring(p,p+4),16));}catch(Exception x){throw bad();}p+=4;}default->throw bad();}}else b.append(c);}throw bad();}
    }
}
