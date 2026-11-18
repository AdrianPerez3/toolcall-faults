package toolcallfaults.runner.fixture;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import toolcallfaults.server.*;

public final class FixtureLoader {
    private FixtureLoader() {}
    public static final ObjectMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static JsonNode read(Path file) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        return JSON.readTree(text);
    }
    private static void fields(JsonNode n, String path, String names) throws IOException {
        if (!n.isObject()) throw new IOException(path + ": expected object");
        Set<String> allowed = Set.of(names.split(" "));
        var keys = n.fieldNames();
        while (keys.hasNext()) { String key = keys.next(); if (!allowed.contains(key)) throw new IOException(path + "." + key + ": unknown field"); }
    }
    private static String text(JsonNode n, String key, String path) throws IOException {
        if (!n.path(key).isTextual()) throw new IOException(path + "." + key + ": expected string");
        return n.get(key).asText();
    }
    private static List<String> strings(JsonNode n, String path) throws IOException {
        if (!n.isArray()) throw new IOException(path + ": expected array");
        List<String> result = new ArrayList<>();
        for (JsonNode item : n) { if (!item.isTextual()) throw new IOException(path + ": expected strings"); result.add(item.asText()); }
        return List.copyOf(result);
    }
    public static Fixture load(Path file) throws IOException {
        JsonNode n = read(file);
        fields(n,"$","format id scenario wire variant tier provenance derived_from call exchanges x truth removes");
        if (!text(n,"format","$").equals("tcf.fixture/v1")) throw new IOException("$.format: expected tcf.fixture/v1");
        String id=text(n,"id","$"), scenario=text(n,"scenario","$"), wire=text(n,"wire","$"), tier=text(n,"tier","$");
        if (!Set.of("synthetic","derived","recorded").contains(tier)) throw new IOException("$.tier: unknown tier");
        JsonNode provenance=n.path("provenance"), call=n.path("call"), truth=n.path("truth");
        fields(provenance,"$.provenance","source source_licence shape_source evidence built_by body_sha256 licence notes recorded_at requested_model returned_model request");
        if (n.hasNonNull("derived_from")) fields(n.get("derived_from"),"$.derived_from","fixture transform arguments rationale evidence");
        fields(call,"$.call","stream tools prompt");
        if (!call.path("stream").isBoolean()) throw new IOException("$.call.stream: expected boolean");
        fields(truth,"$.truth","calls raw_finish stream_completed damage");
        List<TruthCall> calls=new ArrayList<>();
        if (!truth.path("calls").isArray()) throw new IOException("$.truth.calls: expected array");
        for (JsonNode c : truth.path("calls")) {
            fields(c,"$.truth.calls[]","id name arguments_raw complete intended_arguments");
            if (!c.path("complete").isBoolean()) throw new IOException("$.truth.calls[].complete: expected boolean");
            calls.add(new TruthCall(text(c,"id","$.truth.calls[]"),text(c,"name","$.truth.calls[]"),
                    text(c,"arguments_raw","$.truth.calls[]"),c.get("complete").asBoolean(),c.get("intended_arguments")));
        }
        List<JsonNode> exchanges=new ArrayList<>();
        if (!n.path("exchanges").isArray() || n.path("exchanges").isEmpty()) throw new IOException("$.exchanges: expected nonempty array");
        int index=0;
        for (JsonNode e : n.get("exchanges")) {
            String p="$.exchanges["+index+++ "]";
            fields(e,p,"match response"); fields(e.path("match"),p+".match","method path");
            text(e.path("match"),"method",p+".match"); text(e.path("match"),"path",p+".match");
            JsonNode r=e.path("response"), b=r.path("body");
            fields(r,p+".response","status headers body end");fields(b,p+".response.body","sse ndjson chunks_b64 json text");
            if (b.size()!=1) throw new IOException(p+".response.body: expected exactly one body form");
            if (!r.path("status").isInt() || !r.path("headers").isObject()) throw new IOException(p+".response: expected status and headers");
            if (!Set.of("clean","abort").contains(text(r,"end",p+".response"))) throw new IOException(p+".response.end: expected clean or abort");
            String form=b.fieldNames().next();
            if (Set.of("sse","ndjson","chunks_b64").contains(form)) {
                for (String value : strings(b.get(form),p+".response.body."+form)) if (form.equals("chunks_b64")) {
                    try { Base64.getDecoder().decode(value); } catch (IllegalArgumentException ex) { throw new IOException(p+".response.body.chunks_b64: invalid base64",ex); }
                }
            } else if (form.equals("text") && !b.get(form).isTextual()) throw new IOException(p+".response.body.text: expected string");
            exchanges.add(e);
        }
        if (!n.path("x").isObject()) throw new IOException("$.x: expected object");
        var overrides=n.get("x").fields();
        while (overrides.hasNext()) {
            var entry=overrides.next();String p="$.x."+entry.getKey();
            if (!entry.getKey().matches("0|[1-9][0-9]*") || Long.parseLong(entry.getKey())>=exchanges.size()) throw new IOException(p+": invalid exchange index");
            fields(entry.getValue(),p,"end stall_ms delays_ms");
            JsonNode x=entry.getValue();
            if (x.has("end") && !Set.of("stall","reset").contains(x.get("end").asText())) throw new IOException(p+".end: expected stall or reset");
            if (x.path("end").asText().equals("stall") && (!x.path("stall_ms").canConvertToLong() || x.path("stall_ms").asLong()<=0)) throw new IOException(p+".stall_ms: expected positive duration");
            if (x.has("delays_ms")) {
                if (!x.get("delays_ms").isArray()) throw new IOException(p+".delays_ms: expected array");
                for (JsonNode delay : x.get("delays_ms")) if (!delay.isInt() || delay.asInt()<0) throw new IOException(p+".delays_ms: expected nonnegative integers");
            }
        }
        return new Fixture(id,scenario,wire,id.contains("@")?id.substring(id.indexOf('@')+1):"",tier,call.get("stream").asBoolean(),
                strings(call.path("tools"),"$.call.tools"),text(call,"prompt","$.call"),List.copyOf(exchanges),n.get("x"),
                new Truth(List.copyOf(calls),truth.path("raw_finish").isNull()?null:truth.path("raw_finish").asText(null),
                        truth.path("stream_completed").isBoolean()?truth.get("stream_completed").asBoolean():null,text(truth,"damage","$.truth")),
                strings(n.path("removes"),"$.removes"),strings(provenance.path("body_sha256"),"$.provenance.body_sha256"),file);
    }
    /** Validate the byte receipts before a catalogue fixture is used in an observation. */
    public static Fixture loadVerified(Path file) throws IOException {
        Fixture fixture = load(file);
        if (fixture.bodySha256().size() != fixture.exchanges().size())
            throw new IOException(file + ": $.provenance.body_sha256: one hash per exchange required");
        for (int i = 0; i < fixture.exchanges().size(); i++) {
            JsonNode body = fixture.exchanges().get(i).path("response").path("body");
            if (!bodyHash(body).equals(fixture.bodySha256().get(i)))
                throw new IOException(file + ": $.provenance.body_sha256[" + i + "]: body hash mismatch");
        }
        return fixture;
    }

    public static String bodyHash(JsonNode body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts(body)) digest.update(part);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static List<byte[]> parts(JsonNode body) {
        String form=body.fieldNames().next();JsonNode value=body.get(form);List<byte[]> parts=new ArrayList<>();
        try {
            switch (form) {
                case "sse", "ndjson", "chunks_b64" -> { for (JsonNode n : value) parts.add(form.equals("chunks_b64")?
                        Base64.getDecoder().decode(n.asText()):(n.asText()+(form.equals("ndjson")?"\n":"")).getBytes(StandardCharsets.UTF_8)); }
                case "json" -> parts.add(JSON.writeValueAsBytes(value));
                case "text" -> parts.add(value.asText().getBytes(StandardCharsets.UTF_8));
                default -> throw new IllegalArgumentException("Unknown body form");
            }
            return List.copyOf(parts);
        } catch (IOException e) { throw new IllegalArgumentException("Cannot encode body",e); }
    }
    public static byte[] bodyBytes(Fixture f,int index) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(byte[] bytes:parts(f.exchanges().get(index).path("response").path("body"))) out.writeBytes(bytes);
        return out.toByteArray();
    }
    public static Script toScript(Fixture f) {
        List<Exchange> exchanges=new ArrayList<>();int i=0;
        for(JsonNode e:f.exchanges()) {
            JsonNode r=e.get("response"),x=f.x().path(Integer.toString(i++)),b=r.get("body");
            Map<String,String> headers=new LinkedHashMap<>();r.get("headers").fields().forEachRemaining(h->headers.put(h.getKey(),h.getValue().asText()));
            int[] delays=x.has("delays_ms")?JSON.convertValue(x.get("delays_ms"),int[].class):null;
            exchanges.add(new Exchange(e.path("match").path("method").asText(),e.path("match").path("path").asText(),r.get("status").asInt(),headers,
                    b.has("json")||b.has("text")?BodyKind.WHOLE:BodyKind.STREAMED,parts(b),
                    End.valueOf(x.path("end").asText(r.get("end").asText()).toUpperCase(Locale.ROOT)),x.path("stall_ms").asLong(),delays));
        }
        return new Script(exchanges);
    }
}
