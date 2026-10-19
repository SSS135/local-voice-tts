package org.localvoice.tts.backend;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Java port of the Silero v5_6_ru stress frontend (SileroStress: HomoSolver, then AccentorNgram) with the
 * package defaults put_stress, put_stress_homo, put_yo, put_yo_homo and stress_single_vowel all true.
 * Weights and dictionaries come from research/silero-ru/export_frontend.py.
 */
final class SileroStress {
    private static final String VOWELS = "аоуыэиеяёю";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern ACC_SPLIT = Pattern.compile("[\\s.,!?;:<>=()/\\\\]+");
    private static final Pattern NON_CYRILLIC = Pattern.compile("[^А-Яа-яёЁ]");
    private static final Pattern HOMO_WORD = Pattern.compile("(?=.*[а-яё])[а-яё+]+", FLAGS);
    private static final Pattern REMOVE_EXTRA = Pattern.compile("[^a-zA-Zа-яА-ЯёЁ0-9\\s.!?,\\-]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern DOUBLE_DASH = Pattern.compile("-{2,}");
    private static final Pattern REPEAT_PUNCT = Pattern.compile("([.!?])\\1+");
    private static final Pattern REPEAT_COMMA = Pattern.compile(",{2,}");
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([.,!?])");
    private static final Pattern PUNCT_ADD_SPACE = Pattern.compile("([.,!?])(?=\\S)");

    // n-gram accentor
    private final Map<String,Integer> ngrams;
    private final int unk;
    private final float[] accEmbedding;
    private final Mlp stressClf, yoClf;
    private final Map<String,int[]> exceptions = new HashMap<>();
    // homograph solver
    private final Map<String,List<String>> homodict = new HashMap<>();
    private final Map<String,String[]> phraseVariants = new HashMap<>();
    private final Map<String,String> phraseSources = new HashMap<>();
    private final Map<String,Pattern> phrasePatterns = new HashMap<>();
    private final Map<String,Integer> vocab;
    private final int window;
    private final Bert bert;

    SileroStress(File weights, File data) throws IOException {
        Map<String,Tensor> t = readTensors(weights);
        JSONObject root;
        try {
            root = new JSONObject(new String(Files.readAllBytes(data.toPath()), StandardCharsets.UTF_8));
            JSONObject acc = root.getJSONObject("accentor");
            ngrams = index(acc.getJSONArray("ngrams"));
            Integer unkId = ngrams.get("UNK");
            if (unkId == null) throw new IOException("Silero accentor lacks UNK");
            unk = unkId;
            JSONObject exc = acc.getJSONObject("exceptions");
            for (Iterator<String> it = exc.keys(); it.hasNext(); ) {
                String word = it.next();
                JSONArray pair = exc.getJSONArray(word);
                exceptions.put(word, new int[]{pair.getInt(0), pair.getInt(1)});
            }
            JSONObject homo = root.getJSONObject("homosolver");
            JSONObject dict = homo.getJSONObject("homodict");
            for (Iterator<String> it = dict.keys(); it.hasNext(); ) {
                String word = it.next();
                JSONArray variants = dict.getJSONArray(word);
                List<String> list = new ArrayList<>();
                for (int i = 0; i < variants.length(); i++) list.add(variants.getString(i));
                Collections.sort(list); // Python sorted(): code-point order, equal to UTF-16 order for Cyrillic
                homodict.put(word, list);
            }
            JSONObject phrases = homo.getJSONObject("phrases");
            for (Iterator<String> it = phrases.keys(); it.hasNext(); ) {
                String word = it.next();
                JSONObject row = phrases.getJSONObject(word);
                JSONArray variants = row.getJSONArray("variants");
                String[] list = new String[variants.length()];
                for (int i = 0; i < list.length; i++) list[i] = variants.getString(i);
                phraseVariants.put(word, list);
                phraseSources.put(word, row.getString("regex"));
            }
            vocab = index(homo.getJSONArray("vocab"));
            window = homo.getInt("window");
            if (homo.getInt("heads") != 12 || !"[Linear, ReLU, Dropout, Linear]".equals(homo.getJSONArray("homo_clf").toString().replace("\"", "").replace(",", ", ")))
                throw new IOException("Unsupported Silero homograph model layout");
            bert = new Bert(t, homo.getInt("layers"), homo.getInt("heads"), (float) homo.getDouble("emb_ln_eps"), (float) homo.getDouble("ln_eps"));
        } catch (JSONException invalid) {
            throw new IOException("Invalid Silero frontend data", invalid);
        }
        accEmbedding = t.get("acc.embedding").f;
        stressClf = new Mlp(t, "acc.stress");
        yoClf = new Mlp(t, "acc.yo");
        if (accEmbedding.length != ngrams.size() * 16) throw new IOException("Silero accentor size mismatch");
    }

    /** SileroStress.__call__: homographs first, then the n-gram accentor on the result. */
    String accentuate(String sentence) {
        return accentor(homosolver(sentence));
    }

    // ---- AccentorNgram ----

    String accentor(String sentence) {
        List<String> raws = new ArrayList<>(), cleans = new ArrayList<>();
        List<Boolean> mask = new ArrayList<>();
        tokenize(sentence, raws, cleans, mask);
        StringBuilder out = new StringBuilder(sentence.length() + 16);
        for (int w = 0; w < raws.size(); w++) {
            String raw = raws.get(w), clean = cleans.get(w), lower = raw.toLowerCase(Locale.ROOT);
            if (!mask.get(w)) { out.append(raw); continue; }
            boolean haveStress = lower.indexOf('+') >= 0, haveYo = lower.indexOf('ё') >= 0;
            if (haveStress && haveYo) { out.append(raw); continue; }
            if (!haveStress && haveYo) {
                // stress_single_vowel is true, so every user-written ё receives the stress mark.
                StringBuilder marked = new StringBuilder(raw);
                int inserted = 0;
                for (int i = 0; i < lower.length(); i++) if (lower.charAt(i) == 'ё') marked.insert(i + inserted++, '+');
                out.append(marked);
                continue;
            }
            int[] exception = exceptions.get(clean);
            if (exception != null) { out.append(exception(raw, haveStress, exception[0], exception[1])); continue; }
            float[] stressProbs = stressClf.softmax(embed(clean)), yoProbs = yoClf.softmax(embed(clean));
            int stressPred = argmax(stressProbs), yoPred = argmax(yoProbs);
            boolean setStress = stressProbs[stressPred] > 0.5f && !haveStress;
            boolean setYo = yoProbs[yoPred] > 0.5f;
            List<Integer> stressed = new ArrayList<>();
            if (haveStress) {
                for (String part : lower.split("\\+", -1)) stressed.add(countVowels(part));
            } else {
                stressed.add(stressPred);
            }
            List<Integer> vowelIds = new ArrayList<>(), yeIds = new ArrayList<>();
            for (int i = 0; i < lower.length(); i++) {
                if (VOWELS.indexOf(lower.charAt(i)) >= 0) vowelIds.add(i);
                if (lower.charAt(i) == 'е') yeIds.add(i);
            }
            if (vowelIds.isEmpty()) { out.append(raw); continue; }
            List<Integer> stressPositions = new ArrayList<>();
            for (int idx : stressed) if (idx < vowelIds.size()) stressPositions.add(vowelIds.get(idx));
            if (yoPred > 0 && yoPred - 1 < yeIds.size()) {
                int yoPos = yeIds.get(yoPred - 1);
                if (stressPositions.contains(yoPos) && setYo && lower.charAt(yoPos) == 'е')
                    raw = raw.substring(0, yoPos) + (Character.isLowerCase(raw.charAt(yoPos)) ? 'ё' : 'Ё') + raw.substring(yoPos + 1);
            }
            if (vowelIds.size() == 1) { stressPositions = Collections.singletonList(vowelIds.get(0)); setStress = true; }
            if (!haveStress && setStress) {
                StringBuilder marked = new StringBuilder(raw);
                for (int i = 0; i < stressPositions.size(); i++) marked.insert(stressPositions.get(i) + i, '+');
                raw = marked.toString();
            }
            out.append(raw);
        }
        return out.toString();
    }

    private static String exception(String raw, boolean haveStress, int stress, int yo) {
        if (haveStress) {
            List<Integer> marks = new ArrayList<>();
            for (int i = 0; i < raw.length(); i++) if (raw.charAt(i) == '+') marks.add(i);
            StringBuilder word = new StringBuilder(raw.replace("+", ""));
            if (yo != -1 && marks.contains(yo + 1))
                word.setCharAt(yo, Character.isLowerCase(word.charAt(yo)) ? 'ё' : 'Ё');
            for (int pos : marks) word.insert(pos, '+');
            return word.toString();
        }
        if (yo != -1) raw = raw.substring(0, yo) + (Character.isLowerCase(raw.charAt(yo)) ? 'ё' : 'Ё') + raw.substring(yo + 1);
        return raw.substring(0, stress) + '+' + raw.substring(stress);
    }

    /** AccentorNgram._tokenize: re.split with a capturing separator group, then hyphen parts. */
    private static void tokenize(String sentence, List<String> raws, List<String> cleans, List<Boolean> mask) {
        List<String> items = new ArrayList<>();
        Matcher m = ACC_SPLIT.matcher(sentence);
        int last = 0;
        while (m.find()) { items.add(sentence.substring(last, m.start())); items.add(m.group()); last = m.end(); }
        items.add(sentence.substring(last));
        for (String item : items) {
            String[] parts = item.split("-", -1);
            for (int i = 0; i < parts.length; i++) {
                boolean lastPart = i == parts.length - 1;
                String token = lastPart ? parts[i] : parts[i] + "-";
                // Backward compatibility upstream: the "то" of "что-то" is never predicted.
                boolean predict = parts.length == 1 || !lastPart || !parts[i].equals("то");
                String clean = NON_CYRILLIC.matcher(token.toLowerCase(Locale.ROOT)).replaceAll("");
                raws.add(token); cleans.add(clean); mask.add(predict && !clean.isEmpty());
            }
        }
    }

    /** NewFastTextEmbeddingBag: mean of known n-grams (lengths 1..len+2) of "<word>". */
    private float[] embed(String word) {
        String padded = "<" + word + ">";
        float[] sum = new float[16];
        int count = 0;
        for (int n = 1; n <= word.length() + 3; n++) {
            for (int i = 0; i + n <= padded.length(); i++) {
                Integer id = ngrams.get(padded.substring(i, i + n));
                if (id != null) { add(sum, id); count++; }
            }
        }
        if (count == 0) { add(sum, unk); count = 1; }
        for (int i = 0; i < 16; i++) sum[i] /= count;
        return sum;
    }

    private void add(float[] sum, int id) {
        for (int i = 0; i < 16; i++) sum[i] += accEmbedding[id * 16 + i];
    }

    // ---- HomoSolver ----

    String homosolver(String sentence) {
        List<int[]> spans = new ArrayList<>();
        List<String> words = new ArrayList<>(), regexPreds = new ArrayList<>();
        List<Boolean> neural = new ArrayList<>();
        List<int[]> batch = new ArrayList<>();
        Matcher m = HOMO_WORD.matcher(sentence);
        while (m.find()) {
            String word = m.group(), lower = word.toLowerCase(Locale.ROOT);
            if (!homodict.containsKey(lower) && !phraseVariants.containsKey(lower)) continue;
            String before = cleanText(sentence.substring(0, m.start()), true);
            String after = cleanText(sentence.substring(m.end()), false);
            before = before.substring(Math.max(0, before.length() - window / 2));
            after = after.substring(0, Math.min(after.length(), window / 2));
            String marked = (before + " [HOMO] " + lower + " [/HOMO] " + after).trim();
            String regexPred = phrase(lower, marked);
            if (regexPred == null) {
                if (!homodict.containsKey(lower)) continue;
                batch.add(encode(marked));
            } else {
                regexPreds.add(regexPred);
            }
            spans.add(new int[]{m.start(), m.end()});
            words.add(word);
            neural.add(regexPred == null);
        }
        if (words.isEmpty()) return sentence;
        float[] logits = bert.classify(batch, vocab.get("[HOMO]"), vocab.get("[/HOMO]"));
        StringBuilder out = new StringBuilder(sentence);
        int offset = 0, regexIdx = 0, neuralIdx = 0;
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i), pred;
            if (neural.get(i)) {
                // torch.round(sigmoid(x)) rounds half to even: exactly 0.5 selects variant 0.
                pred = homodict.get(word.toLowerCase(Locale.ROOT)).get(logits[neuralIdx++] > 0 ? 1 : 0);
            } else {
                pred = regexPreds.get(regexIdx++);
            }
            int stress = pred.indexOf('+');
            String plain = pred.replace("+", "");
            StringBuilder cased = new StringBuilder();
            for (int c = 0; c < Math.min(word.length(), plain.length()); c++) {
                char ch = plain.charAt(c);
                cased.append(Character.isLowerCase(word.charAt(c)) ? Character.toLowerCase(ch) : Character.toUpperCase(ch));
            }
            // n_vowels > 1 or stress_single_vowel (true): the mark is always restored.
            cased.insert(Math.min(stress, cased.length()), '+');
            int start = spans.get(i)[0] + offset, end = spans.get(i)[1] + offset;
            out.replace(start, end, cased.toString());
            offset += cased.length() - (end - start);
        }
        return out.toString();
    }

    private String phrase(String word, String marked) {
        String source = phraseSources.get(word);
        if (source == null) return null;
        Pattern pattern = phrasePatterns.get(word);
        if (pattern == null) { pattern = Pattern.compile(source, FLAGS); phrasePatterns.put(word, pattern); }
        Matcher m = pattern.matcher(marked);
        if (!m.find()) return null;
        String[] variants = phraseVariants.get(word);
        for (int g = 1; g <= Math.min(m.groupCount(), variants.length); g++) if (m.group(g) != null) return variants[g - 1];
        return null;
    }

    /** HomoSolver._clean_text. */
    static String cleanText(String text, boolean isStart) {
        if (text.isEmpty()) return "";
        text = REMOVE_EXTRA.matcher(text).replaceAll("");
        text = SPACES.matcher(text).replaceAll(" ");
        text = DOUBLE_DASH.matcher(text).replaceAll(" - ");
        text = REPEAT_PUNCT.matcher(text).replaceAll("$1");
        text = REPEAT_COMMA.matcher(text).replaceAll(",");
        text = SPACE_BEFORE_PUNCT.matcher(text).replaceAll("$1");
        text = REPEAT_PUNCT.matcher(text).replaceAll("$1");
        text = REPEAT_COMMA.matcher(text).replaceAll(",");
        text = PUNCT_ADD_SPACE.matcher(text).replaceAll("$1 ");
        text = SPACES.matcher(text).replaceAll(" ").trim();
        if (isStart) {
            int s = 0;
            while (s < text.length() && " .,!?-".indexOf(text.charAt(s)) >= 0) s++;
            text = text.substring(s);
            if (!text.isEmpty()) text = text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1).toLowerCase(Locale.ROOT);
        } else {
            if (!text.isEmpty()) text = text.charAt(0) + text.substring(1).toLowerCase(Locale.ROOT);
            if (!text.isEmpty() && ".!?".indexOf(text.charAt(text.length() - 1)) < 0) text += ".";
        }
        return text;
    }

    /** SimpleBertTokenizer.encode: no lower-casing, punctuation split, greedy WordPiece. */
    int[] encode(String text) {
        List<Integer> ids = new ArrayList<>();
        ids.add(vocab.get("[CLS]"));
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0 || cp == 0xfffd || isControl(cp)) continue;
            if (cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r' || Character.getType(cp) == Character.SPACE_SEPARATOR) clean.append(' ');
            else if (isCjk(cp)) clean.append(' ').appendCodePoint(cp).append(' ');
            else clean.appendCodePoint(cp);
        }
        int unkId = vocab.get("[UNK]");
        for (String token : clean.toString().trim().split(" +")) {
            if (token.isEmpty()) continue;
            List<String> pieces = new ArrayList<>();
            if (token.equals("[HOMO]") || token.equals("[/HOMO]")) pieces.add(token);
            else splitPunctuation(token, pieces);
            for (String piece : pieces) wordpiece(piece, ids, unkId);
        }
        ids.add(vocab.get("[SEP]"));
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    private void wordpiece(String word, List<Integer> ids, int unkId) {
        int[] cps = word.codePoints().toArray();
        if (cps.length > 100) { ids.add(unkId); return; }
        List<Integer> sub = new ArrayList<>();
        int start = 0;
        while (start < cps.length) {
            Integer found = null;
            int end = cps.length;
            for (; start < end; end--) {
                String piece = new String(cps, start, end - start);
                found = vocab.get(start > 0 ? "##" + piece : piece);
                if (found != null) break;
            }
            if (found == null) { ids.add(unkId); return; }
            sub.add(found);
            start = end;
        }
        ids.addAll(sub);
    }

    private static void splitPunctuation(String token, List<String> out) {
        StringBuilder current = null;
        for (int i = 0; i < token.length(); ) {
            int cp = token.codePointAt(i);
            i += Character.charCount(cp);
            if (isPunctuation(cp)) {
                if (current != null) { out.add(current.toString()); current = null; }
                out.add(new String(Character.toChars(cp)));
            } else {
                if (current == null) current = new StringBuilder();
                current.appendCodePoint(cp);
            }
        }
        if (current != null) out.add(current.toString());
    }

    private static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) return true;
        switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION: case Character.DASH_PUNCTUATION: case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION: case Character.INITIAL_QUOTE_PUNCTUATION: case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION: return true;
            default: return false;
        }
    }

    private static boolean isControl(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r') return false;
        switch (Character.getType(cp)) {
            case Character.CONTROL: case Character.FORMAT: case Character.SURROGATE: case Character.PRIVATE_USE: case Character.UNASSIGNED: return true;
            default: return false;
        }
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x20000 && cp <= 0x2A6DF)
            || (cp >= 0x2A700 && cp <= 0x2B73F) || (cp >= 0x2B740 && cp <= 0x2B81F) || (cp >= 0x2B820 && cp <= 0x2CEAF)
            || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }

    private static int countVowels(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (VOWELS.indexOf(s.charAt(i)) >= 0) n++;
        return n;
    }

    private static int argmax(float[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[best]) best = i;
        return best;
    }

    private static Map<String,Integer> index(JSONArray rows) throws JSONException {
        Map<String,Integer> map = new HashMap<>(rows.length() * 2);
        for (int i = 0; i < rows.length(); i++) map.put(rows.getString(i), i);
        return map;
    }

    // ---- tensors and layers ----

    static final class Tensor {
        final int[] shape;
        final float[] f;
        final byte[] b;
        Tensor(int[] shape, float[] f, byte[] b) { this.shape = shape; this.f = f; this.b = b; }
    }

    /** frontend.bin: "SLRF", u32 header length, JSON header, 4-byte aligned little-endian tensors. */
    static Map<String,Tensor> readTensors(File file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[8];
            in.readFully(magic);
            ByteBuffer head = ByteBuffer.wrap(magic).order(ByteOrder.LITTLE_ENDIAN);
            if (head.get(0) != 'S' || head.get(1) != 'L' || head.get(2) != 'R' || head.get(3) != 'F') throw new IOException("Not a Silero frontend file");
            int headerLength = head.getInt(4);
            if (headerLength <= 0 || headerLength > 1 << 20) throw new IOException("Invalid Silero frontend header");
            byte[] header = new byte[headerLength];
            in.readFully(header);
            long base = 8L + headerLength;
            Map<String,Tensor> out = new HashMap<>();
            JSONObject tensors = new JSONObject(new String(header, StandardCharsets.UTF_8)).getJSONObject("tensors");
            for (Iterator<String> it = tensors.keys(); it.hasNext(); ) {
                String name = it.next();
                JSONObject row = tensors.getJSONObject(name);
                JSONArray dims = row.getJSONArray("shape");
                int[] shape = new int[dims.length()];
                long count = 1;
                for (int i = 0; i < shape.length; i++) { shape[i] = dims.getInt(i); count *= shape[i]; }
                boolean int8 = "i8".equals(row.getString("dtype"));
                long bytes = count * (int8 ? 1 : 4), offset = base + row.getLong("offset");
                if (count > Integer.MAX_VALUE / 4 || offset + bytes > in.length()) throw new IOException("Silero frontend tensor out of range");
                byte[] raw = new byte[(int) bytes];
                in.seek(offset);
                in.readFully(raw);
                if (int8) {
                    out.put(name, new Tensor(shape, null, raw));
                } else {
                    float[] values = new float[(int) count];
                    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
                    out.put(name, new Tensor(shape, values, null));
                }
            }
            return out;
        } catch (JSONException invalid) {
            throw new IOException("Invalid Silero frontend header", invalid);
        }
    }

    private static Tensor need(Map<String,Tensor> t, String name) throws IOException {
        Tensor value = t.get(name);
        if (value == null) throw new IOException("Silero frontend lacks " + name);
        return value;
    }

    /** torch Linear: y = x W^T + b with W [out, in]. */
    static final class Linear {
        final float[] w, b;
        final int in, out;
        Linear(Map<String,Tensor> t, String name) throws IOException {
            Tensor weight = need(t, name + ".weight");
            w = weight.f; b = need(t, name + ".bias").f; out = weight.shape[0]; in = weight.shape[1];
        }
        float[] apply(float[] x, int rows) {
            float[] y = new float[rows * out];
            for (int r = 0; r < rows; r++) {
                int xo = r * in, yo = r * out;
                for (int o = 0; o < out; o++) {
                    float s = b[o];
                    int wo = o * in;
                    for (int i = 0; i < in; i++) s += x[xo + i] * w[wo + i];
                    y[yo + o] = s;
                }
            }
            return y;
        }
    }

    static final class Mlp {
        final Linear[] layers = new Linear[4];
        Mlp(Map<String,Tensor> t, String name) throws IOException {
            for (int i = 0; i < 4; i++) layers[i] = new Linear(t, name + "." + (2 * i));
        }
        float[] softmax(float[] x) {
            for (int i = 0; i < 4; i++) {
                x = layers[i].apply(x, 1);
                if (i < 3) relu(x);
            }
            float max = Float.NEGATIVE_INFINITY, sum = 0;
            for (float v : x) max = Math.max(max, v);
            for (int i = 0; i < x.length; i++) { x[i] = (float) Math.exp(x[i] - max); sum += x[i]; }
            for (int i = 0; i < x.length; i++) x[i] /= sum;
            return x;
        }
    }

    static void relu(float[] x) {
        for (int i = 0; i < x.length; i++) if (x[i] < 0) x[i] = 0;
    }

    static void layerNorm(float[] x, int rows, float[] g, float[] b, float eps) {
        int d = g.length;
        for (int r = 0; r < rows; r++) {
            int o = r * d;
            float mean = 0, var = 0;
            for (int i = 0; i < d; i++) mean += x[o + i];
            mean /= d;
            for (int i = 0; i < d; i++) { float c = x[o + i] - mean; var += c * c; }
            float inv = (float) (1.0 / Math.sqrt(var / d + eps));
            for (int i = 0; i < d; i++) x[o + i] = (x[o + i] - mean) * inv * g[i] + b[i];
        }
    }

    /** Exact (erf) GELU; erf via Abramowitz-Stegun 7.1.26 (|error| < 1.5e-7). */
    static float gelu(float x) {
        double z = Math.abs(x) / Math.sqrt(2.0), t = 1.0 / (1.0 + 0.3275911 * z);
        double erf = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-z * z);
        if (x < 0) erf = -erf;
        return (float) (0.5 * x * (1.0 + erf));
    }

    /** HuggingFace BERT encoder (post-LN, absolute positions, token type 0, no attention mask) plus homo_clf. */
    static final class Bert {
        final byte[] word;
        final float wordScale, wordZero;
        final float[] pos, type, embG, embB;
        final int hidden, heads, layers;
        final float embEps, eps;
        final Linear[] q, k, v, o, inter, out;
        final float[][] attnG, attnB, outG, outB;
        final Linear clf0, clf3;

        Bert(Map<String,Tensor> t, int layers, int heads, float embEps, float eps) throws IOException {
            Tensor w = need(t, "bert.word");
            word = w.b; hidden = w.shape[1];
            wordScale = need(t, "bert.word_scale").f[0]; wordZero = need(t, "bert.word_zero").f[0];
            pos = need(t, "bert.pos").f; type = need(t, "bert.type").f;
            embG = need(t, "bert.emb_ln.weight").f; embB = need(t, "bert.emb_ln.bias").f;
            this.layers = layers; this.heads = heads; this.embEps = embEps; this.eps = eps;
            q = new Linear[layers]; k = new Linear[layers]; v = new Linear[layers]; o = new Linear[layers];
            inter = new Linear[layers]; out = new Linear[layers];
            attnG = new float[layers][]; attnB = new float[layers][]; outG = new float[layers][]; outB = new float[layers][];
            for (int l = 0; l < layers; l++) {
                String p = "bert." + l + ".";
                q[l] = new Linear(t, p + "q"); k[l] = new Linear(t, p + "k"); v[l] = new Linear(t, p + "v"); o[l] = new Linear(t, p + "o");
                inter[l] = new Linear(t, p + "inter"); out[l] = new Linear(t, p + "out");
                attnG[l] = need(t, p + "attn_ln.weight").f; attnB[l] = need(t, p + "attn_ln.bias").f;
                outG[l] = need(t, p + "out_ln.weight").f; outB[l] = need(t, p + "out_ln.bias").f;
            }
            clf0 = new Linear(t, "homo.0"); clf3 = new Linear(t, "homo.3");
            if (hidden % heads != 0 || clf0.in != 2 * hidden) throw new IOException("Unsupported Silero BERT shape");
        }

        /** HomoSolverClean.forward on a pad_sequence batch (pad id 0, attended exactly as upstream). */
        float[] classify(List<int[]> batch, int startId, int endId) {
            float[] logits = new float[batch.size()];
            int maxLen = 0;
            for (int[] ids : batch) maxLen = Math.max(maxLen, ids.length);
            for (int n = 0; n < batch.size(); n++) {
                int[] ids = batch.get(n);
                int start = -1, end = -1;
                for (int i = 0; i < ids.length; i++) {
                    if (ids[i] == startId && start < 0) start = i;
                    if (ids[i] == endId && end < 0) end = i;
                }
                if (start < 0 || end <= start + 1) throw new IllegalStateException("Silero homograph markers missing");
                float[] h = encode(java.util.Arrays.copyOf(ids, maxLen));
                float[] feat = new float[2 * hidden];
                System.arraycopy(h, start * hidden, feat, 0, hidden);
                for (int i = start + 1; i < end; i++) for (int c = 0; c < hidden; c++) feat[hidden + c] += h[i * hidden + c];
                for (int c = 0; c < hidden; c++) feat[hidden + c] /= (end - start - 1);
                float[] z = clf0.apply(feat, 1);
                relu(z);
                logits[n] = clf3.apply(z, 1)[0];
            }
            return logits;
        }

        float[] encode(int[] ids) {
            int len = ids.length, d = hidden, hd = d / heads;
            if (len * d > pos.length) throw new IllegalArgumentException("Silero homograph context too long");
            float[] x = new float[len * d];
            for (int i = 0; i < len; i++) {
                int wo = ids[i] * d;
                for (int c = 0; c < d; c++) x[i * d + c] = wordScale * ((float) word[wo + c] - wordZero) + type[c] + pos[i * d + c];
            }
            layerNorm(x, len, embG, embB, embEps);
            float scale = (float) Math.sqrt(hd);
            for (int l = 0; l < layers; l++) {
                float[] qs = q[l].apply(x, len), ks = k[l].apply(x, len), vs = v[l].apply(x, len);
                float[] ctx = new float[len * d], scores = new float[len];
                for (int h = 0; h < heads; h++) {
                    int ho = h * hd;
                    for (int i = 0; i < len; i++) {
                        float max = Float.NEGATIVE_INFINITY;
                        for (int j = 0; j < len; j++) {
                            float s = 0;
                            for (int c = 0; c < hd; c++) s += qs[i * d + ho + c] * ks[j * d + ho + c];
                            scores[j] = s / scale;
                            max = Math.max(max, scores[j]);
                        }
                        float sum = 0;
                        for (int j = 0; j < len; j++) { scores[j] = (float) Math.exp(scores[j] - max); sum += scores[j]; }
                        for (int j = 0; j < len; j++) {
                            float p = scores[j] / sum;
                            for (int c = 0; c < hd; c++) ctx[i * d + ho + c] += p * vs[j * d + ho + c];
                        }
                    }
                }
                float[] attn = o[l].apply(ctx, len);
                for (int i = 0; i < attn.length; i++) attn[i] += x[i];
                layerNorm(attn, len, attnG[l], attnB[l], eps);
                float[] mid = inter[l].apply(attn, len);
                for (int i = 0; i < mid.length; i++) mid[i] = gelu(mid[i]);
                float[] y = out[l].apply(mid, len);
                for (int i = 0; i < y.length; i++) y[i] += attn[i];
                layerNorm(y, len, outG[l], outB[l], eps);
                x = y;
            }
            return x;
        }
    }
}
