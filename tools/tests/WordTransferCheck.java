import io.github.doubaoa11y.ui.WordTransferFormat;
import io.github.doubaoa11y.hook.CharWords;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 仅在 JVM 内校验格式、真实内置词导出和合并计划，不接触偏好、手机或读屏。 */
public final class WordTransferCheck {
    private static int count;
    private static final WordTransferFormat F = WordTransferFormat.INSTANCE;
    private static byte[] utf(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean ok) { if (!ok) throw new AssertionError("WordTransfer check " + (count+1)); count++; }
    private static void reject(byte[] bytes) {
        try { F.parse(bytes); } catch (Exception expected) { count++; return; }
        throw new AssertionError("accepted invalid words");
    }
    private static void reject(String text) { reject(utf(text)); }
    private static Map<String,String> words(String... values) {
        Map<String,String> out = new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2) out.put(values[i], values[i+1]);
        return out;
    }
    private static String repeat(char c, int times) { char[] data=new char[times]; Arrays.fill(data,c); return new String(data); }
    public static void main(String[] args) throws Exception {
        io.github.doubaoa11y.hook.CustomWordState state = new io.github.doubaoa11y.hook.CustomWordState();
        check(!state.applyWords(null));
        check(state.applyWords("高\t提高"));
        check(state.applyWords("") && state.getCustomWords().isEmpty());
        check(state.applyWords("𠀀\t扩展字") && state.getCustomWords().equals(words("𠀀","扩展字")));
        check(!state.applyWords("𠀀\t扩展字"));
        check(state.applyWords("高\t提高") && state.getCustomWords().equals(words("高","提高")));
        check(F.parse(utf("㐀\t扩展字\n𠀀\t补充字")).equals(words("㐀","扩展字","𠀀","补充字")));
        Map<String,String> original=words("高","提高","兴","高兴");
        byte[] exported=F.encode(original);
        check(exported[0]==(byte)0xef && exported[1]==(byte)0xbb && exported[2]==(byte)0xbf);
        check(F.parse(exported).equals(original));
        check(new String(exported,StandardCharsets.UTF_8).equals("\uFEFF高\t提高\n兴\t高兴"));
        check(F.serialize(original).equals("高\t提高\n兴\t高兴"));
        check(F.parse(utf("高\t提高\r\n兴\t高兴\r\n")).equals(original));
        check(F.parse(new byte[0]).isEmpty());
        check(F.parse(utf("\uFEFF")).isEmpty());
        check(F.parse(utf("高\t 一 \n")).get("高").equals(" 一 "));
        check(F.parse(utf("高\t"+repeat('词',256))).get("高").length()==256);
        reject("高\t"+repeat('词',257));
        for(String line:Arrays.asList("A\t英文","高高\t提高","高提高","高\t","高\t  ","高\t提高\t高","高\t提高\n高\t高兴","高\t提高\n\n兴\t高兴","高\t提\r高","高\t提\u0000高","高\t提\u007f高","高\t提\u0085高","高\t提\u2028高","高\t提\u2029高","高\t提\u200e高","高\t提\uFEFF高")) reject(line);
        reject(new byte[]{(byte)0xc3,0x28});
        reject(new byte[]{(byte)0xff,(byte)0xfe,0x00,0x00});
        reject(new byte[]{(byte)0xed,(byte)0xa0,(byte)0x80});
        reject(new byte[WordTransferFormat.MAX_BYTES+1]);
        reject("高\t提高\n"+repeat(' ',WordTransferFormat.MAX_CHARS));
        check(F.read(new ByteArrayInputStream(exported)).equals(original));
        try { F.read(new InputStream() { int left=WordTransferFormat.MAX_BYTES+1; public int read(){return left-->0?'a':-1;} }); throw new AssertionError(); }
        catch(IllegalArgumentException expected) {count++;}
        try { F.read(new InputStream() { public int read() throws IOException {throw new IOException("injected read failure");} }); throw new AssertionError(); }
        catch(Exception expected) {check(expected instanceof IOException);}
        check(F.parse(F.encode(words("高","笑脸😀"))).get("高").equals("笑脸😀"));
        try { F.encode(words("高","bad\ud800")); throw new AssertionError(); } catch(IllegalArgumentException expected){count++;}

        Map<String,String> imported=words("高","高处","新","新建");
        WordTransferFormat.Change merge=F.plan(original,imported,false);
        check(merge.getTotal()==3 && merge.getOverwritten()==1 && merge.getRemoved()==0);
        check(F.parse(utf(merge.getText())).equals(words("高","高处","兴","高兴","新","新建")));
        WordTransferFormat.Change replace=F.plan(original,imported,true);
        check(replace.getTotal()==2 && replace.getOverwritten()==1 && replace.getRemoved()==1);
        check(F.parse(utf(replace.getText())).equals(imported));
        check(F.plan(original,Collections.emptyMap(),false).getText().equals(F.serialize(original)));
        check(F.plan(original,Collections.emptyMap(),true).getText().isEmpty());
        check(original.equals(words("高","提高","兴","高兴")) && imported.equals(words("高","高处","新","新建")));
        Map<String,String> invalid=words("高","bad\tvalue");
        try { F.plan(original,invalid,false); throw new AssertionError(); } catch(IllegalArgumentException expected){count++;}
        check(original.equals(words("高","提高","兴","高兴")));
        Map<String,String> nearLimit=new LinkedHashMap<>();
        for(int i=0;i<772;i++) nearLimit.put(String.valueOf((char)('一'+i)),repeat('词',256));
        check(F.serialize(nearLimit).length()<WordTransferFormat.MAX_CHARS);
        Map<String,String> more=words("高",repeat('词',256));
        try { F.plan(nearLimit,more,false); throw new AssertionError(); } catch(IllegalArgumentException expected){count++;}
        check(nearLimit.size()==772);

        Map<String,String> builtIn=CharWords.INSTANCE.builtIn();
        check(builtIn.size()>10000);
        Map<String,String> all=new LinkedHashMap<>(builtIn);
        all.putAll(original);
        byte[] full=F.encode(all);
        check(F.parse(full).equals(all));
        check(full.length<=WordTransferFormat.MAX_BYTES && F.serialize(all).length()<=WordTransferFormat.MAX_CHARS);
        check(builtIn.get("高").equals(CharWords.INSTANCE.builtIn().get("高")) && all.get("高").equals("提高"));
        System.out.println("PASS "+count+" word format/merge checks; built-in "+builtIn.size()+" entries, full export "+full.length+" bytes");
    }
}
