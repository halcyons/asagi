package net.easymodo.asagi;

import com.google.gson.*;
import net.easymodo.asagi.exception.CfBicClearParseException;
import net.easymodo.asagi.exception.ContentGetException;
import net.easymodo.asagi.model.MediaPost;

import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class YotsubaAbstract extends WWW {
    protected static final Gson GSON = new GsonBuilder().
            registerTypeAdapter(boolean.class, new BooleanTypeConverter()).
            setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();

    private static class BooleanTypeConverter implements JsonSerializer<Boolean>, JsonDeserializer<Boolean> {
        @Override
        public JsonElement serialize(Boolean src, Type srcType, JsonSerializationContext context) {
            return new JsonPrimitive(src ? 1 : 0);
        }

        @Override
        public Boolean deserialize(JsonElement json, Type type, JsonDeserializationContext context)
                throws JsonParseException {
            try {
                return (json.getAsInt() != 0);
            } catch(ClassCastException e) {
                // fourchan api can't make up its mind about this
                return json.getAsBoolean();
            }
        }
    }


    private static final Pattern drawPattern;
    private static final Pattern exifPattern;
    private static final Pattern exifDataPattern;

    static {
        String drawPatString = "<small><b>Oekaki \\s Post</b> \\s \\(Time: \\s (.*?), \\s Painter: \\s (.*?)(?:, \\s Source: \\s (.*?))?(?:, \\s Animation: \\s (.*?))?\\)</small>";
        String exifPatString = "<table \\s class=\"exif\"[^>]*>(.*)</table>";
        String exifDataPatString = "<tr><td>(.*?)</td><td>(.*?)</td></tr>";

        drawPattern = Pattern.compile(drawPatString, Pattern.COMMENTS | Pattern.DOTALL);
        exifPattern = Pattern.compile(exifPatString, Pattern.COMMENTS | Pattern.DOTALL);
        exifDataPattern = Pattern.compile(exifDataPatString, Pattern.COMMENTS | Pattern.DOTALL);
    }

    protected final Map<String,String> boardLinks;

    protected YotsubaAbstract(Map<String,String> boardLinks) {
        this.boardLinks = boardLinks;
    }

    @Override
    public InputStream getMediaPreview(MediaPost h) throws ContentGetException, CfBicClearParseException {
        if(h.getPreview() == null)
            return null;

        return this.wget(this.boardLinks.get("thumbLink") + "/" + h.getPreview());
    }

    @Override
    public InputStream getMedia(MediaPost h) throws ContentGetException, CfBicClearParseException {
        if(h.getMedia() == null)
            return null;

        return this.wget(this.boardLinks.get("imageLink") + "/" + h.getMedia());
    }

    public String cleanSimple(String text) {
        return super.doClean(text);
    }

    public String cleanLink(String text) {
        return super.doCleanLink(super.doClean(text));
    }

    public String doClean(String text) {
        if(text == null) return null;

        // SOPA spoilers
        //text = text.replaceAll("<span class=\"spoiler\"[^>]*>(.*?)</spoiler>(</span>)?", "$1");

        // Admin-Mod-Dev quotelinks
        text = text.replaceAll("<span class=\"capcodeReplies\"><span style=\"font-size: smaller;\"><span style=\"font-weight: bold;\">(?:Administrator|Moderator|Developer) Repl(?:y|ies):</span>.*?</span><br></span>", "");
        // Non-public tags
        text = text.replaceAll("\\[(/?(banned|moot|spoiler|code))]", "[$1:lit]");
        // Comment too long, also EXIF tag toggle
        text = text.replaceAll("<span class=\"abbr\">.*?</span>", "");
        // EXIF data
        text = text.replaceAll("<table class=\"exif\"[^>]*>.*?</table>", "");
        // DRAW data
        text = text.replaceAll("<br><br><small><b>Oekaki Post</b>.*?</small>", "");
        // Banned/Warned text
        text = text.replaceAll("<(?:b|strong) style=\"color:\\s*red;\">(.*?)</(?:b|strong)>", "[banned]$1[/banned]");
        // moot text
        text = text.replaceAll("<div style=\"padding: 5px;margin-left: \\.5em;border-color: #faa;border: 2px dashed rgba\\(255,0,0,\\.1\\);border-radius: 2px\">(.*?)</div>", "[moot]$1[/moot]");
        // fortune text
        text = text.replaceAll("<span class=\"fortune\" style=\"color:(.*?)\"><br><br><b>(.*?)</b></span>", "\n\n[fortune color=\"$1\"]$2[/fortune]");
        // bold text
        text = text.replaceAll("<(?:b|strong)>(.*?)</(?:b|strong)>", "[b]$1[/b]");
        // code tags
        text = text.replaceAll("<pre[^>]*>", "[code]");
        text = text.replaceAll("</pre>", "[/code]");
        // math tags
        text = text.replaceAll("<span class=\"math\">(.*?)</span>", "[math]$1[/math]");
        text = text.replaceAll("<div class=\"math\">(.*?)</div>", "[eqn]$1[/eqn]");
        // > implying I'm quoting someone
        text = text.replaceAll("<font class=\"unkfunc\">(.*?)</font>", "$1");
        text = text.replaceAll("<span class=\"quote\">(.*?)</span>", "$1");
        text = text.replaceAll("<span class=\"(?:[^\"]*)?deadlink\">(.*?)</span>", "$1");
        // Links
        text = text.replaceAll("<a[^>]*>(.*?)</a>", "$1");
        // old spoilers
        text = text.replaceAll("<span class=\"spoiler\"[^>]*>", "[spoiler]");
        text = text.replaceAll("</span>", "[/spoiler]");
        // new spoilers
        text = text.replaceAll("<s>", "[spoiler]");
        text = text.replaceAll("</s>", "[/spoiler]");
        // new line/wbr
        text = text.replaceAll("<br\\s*/?>", "\n");
        text = text.replaceAll("<wbr>", "");

        return this.cleanSimple(text);
    }

    public String parseMeta(String text) {
        return this.parseMeta(text, null, null, null);
    }

    public String parseMeta(String text, Integer uniqueIps, Integer since4pass, String trollCountry) {
        if (text == null && uniqueIps == null && since4pass == null && trollCountry == null) return null;

        Map<String, String> exifJson = new HashMap<String, String>();
        if (text != null) {
            Matcher exif = exifPattern.matcher(text);

            if (exif.find()) {
                String data = exif.group(1);
                // remove empty rows
                data = data.replaceAll("<tr><td colspan=\"2\"></td></tr><tr>", "");

                Matcher exifData = exifDataPattern.matcher(data);

                while (exifData.find()) {
                    String key = exifData.group(1);
                    String val = exifData.group(2);
                    exifJson.put(key, val);
                }
            }

            Matcher draw = drawPattern.matcher(text);
            if (draw.find()) {
                exifJson.put("Time", draw.group(1));
                exifJson.put("Painter", draw.group(2));
                exifJson.put("Source", draw.group(3) != null ? this.doClean(draw.group(3)) : null);
            }
        }

        if (uniqueIps != null && uniqueIps != 0) {
            exifJson.put("uniqueIps", Integer.toString(uniqueIps));
        }

        if (since4pass != null && since4pass != 0) {
            exifJson.put("since4pass", Integer.toString(since4pass));
        }

        if (trollCountry != null && !trollCountry.isEmpty()) {
            exifJson.put("trollCountry", trollCountry);
        }

        if (exifJson.size() > 0)
            return GSON.toJson(exifJson);

        return null;
    }
}
