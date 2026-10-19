# Russian text normalizer

`backend/RussianNormalizer.java` turns arbitrary web text into speakable Russian for Russian backends. Shtorm (`ShtormBackend`) and Silero (`SileroFrontend.prepare`) use it. It is deterministic, rule-based, and pure Java. Number words live in `RussianCardinals.java` and Latin transcription in `LatinTranslit.java`.

**Contract.** `normalize(text)` never throws on content. A rule bug costs one skipped character, and a top-level failure falls back to Russian letters plus punctuation. Output contains only:

- Russian Cyrillic, with case kept and `ё` kept or produced.
- U+0301 stress after a vowel, and Silero `+` stress inside Cyrillic words.
- Space and `\n`.
- The punctuation `. , ! ? : ; - — … ( ) « »`.

Each backend filters further. Silero lower-cases, maps `—` to `–`, and drops `( ) « »`. Shtorm's `encodeSpeakable` drops every code point that is not a single-character vocabulary piece, which covers `+`, emoji, and leftover OOV. Unpaired surrogates, format characters, emoji, and other scripts become word breaks.

**Offset map.** `Result.sourceStart(i)`/`sourceEnd(i)` give, for each output char, the original `[start,end)` it was spoken for. `Result.source(from,to)` covers an output range. An expansion maps to its whole source token: `2026` → `две тысячи двадцать шестом`. A unit word maps to its symbol: `%` → `процентов`, and `$` → `долларов` even when the symbol comes first. Inserted spaces map to the adjacent token.

## Rules (first match wins at each token)

| Input | Output / policy |
|---|---|
| Integers | Cardinals up to `Long` range (квинтиллион). Over 18 digits become groups of three. A leading `0` is read digit by digit. `1000`–`1999` lead with `тысяча`, not `одна тысяча`. Grouping by space, NBSP, thin space, or `'` is accepted for `d{1,3}( ddd)+`. |
| Case | Taken from the preceding preposition. GEN: от, до, из, без, у, около, для, после, более, свыше, … DAT: к, ко, благодаря, согласно. INS: между, перед, над, and `с` + `-ами/-ями`. PRE: о, об, при, and в/на + `-ах/-ях`. `с` defaults to GEN. Everything else is NOM, with accusative spoken as nominative. |
| Gender of 1/2 | From the next word's ending: `а/я` → одна, `о/е` → одно, `у/ю` → одну. In NOM, `и/ы` after 2 → две. Units carry their own gender. |
| Decimals `3,14` `2.5` | `три целых четырнадцать сотых`, declined. Over 6 fractional digits: `… запятая` then digits. Currency with 2 decimals: `рублей … копеек`, or `центов` for $ and €. After `версия/version/v/android/…` a dot is read as `точка`. |
| Ranges `5–7`, `3-5%` | Without a preposition: `от пяти до семи`. After a preposition: both numbers in its case, joined by ` — `. Year-like ranges (1000–2100) become nominative ordinals. With `-х`, `гг.`, or a month, the ordinal or date forms below apply. |
| Ordinal suffix `1-й 2-го 21-я 1990-х 90-е` | The form comes from the suffix. `-й/-ой` is feminine oblique before an `е/и/ы/ой/ей` noun, otherwise masculine nominative. `-м` is PRE after в/на/о, otherwise INS. Decade `-е` is plural. `5-ти`, `7-ми`, `2-х`, `2-м`, `2-мя` are cardinal case endings. |
| Dates `19.10.2026` `19/10/26` `2026-10-19` | `девятнадцатое октября две тысячи двадцать шестого года`. The day form follows the preposition (`до` → девятнадцатого). Two-digit years ≤ 50 are read as 20xx, others as 19xx. `DD.MM` without a year counts as a date only after до/с/по/к/от/после/на. |
| `N <месяц>`, `в октябре 2026`, `2026 г.`, `в 2026 году`, `XX век`, `XIX–XX вв.` | Day ordinal, year ordinal in genitive, and year/century ordinals in the case of the noun. `г.`/`гг.`/`в.`/`вв.` become the noun in the preposition's case. Years need ≥ 1000; `5 г` is grams. |
| Roman numerals | Only before век/в./столетие, after глава/том/часть/раздел/статья/…, or after a capitalized Russian name (`Пётр I`, `Екатерина II`). Otherwise the token is treated as Latin. |
| Time `12:30` `7:05` `12:30:15` | `двенадцать тридцать`, `семь ноль пять`, `… часов … минут … секунд`. `12.30` counts as time only after в/к/до/с/после/около/по. |
| Units/currency | `% ‰ °C °F ° ₽ руб. р. коп. $ долл. € £ ¥ ₴ ₸ тыс. млн млрд трлн км/ч м/с км м см мм кг г мг т л мл ч мин сек с мс шт ГБ МБ КБ ТБ ГГц МГц кГц Гц кВт Вт В`, plus Latin `km kg cm mm GB MB …`. Counts agree (1 рубль / 2 рубля / 5 рублей), fractions take genitive singular, and scale words take the "many" form for the unit. A spaced one-letter unit followed by a word stays a word (`5 с половиной`). |
| Phones, IDs | Phone patterns: `+…`, `8 (…)`, `(…)`, `ddd-dd-dd`. Each digit group is read as a cardinal, groups joined by commas. |
| Versions, IPs `1.13.8` | `один точка тринадцать точка восемь`. Comma lists `1,2,3` are separate numbers. |
| URLs, domains, e-mail | Host only, labels joined by `точка`, `www` and the path dropped: `гугл точка ком`. E-mail: `<user> собака <host>`. |
| Latin words | Dictionary of about 200 words and brands first (Google → Гугл, iPhone → айфон, OK → окей). All-caps ≤ 5 letters, single letters, and vowel-less tokens are spelled with English letter names joined by hyphens (`USA` → `ю-эс-эй`, `mp3` → `эм-пи три`). camelCase parts are read separately. Anything else uses English grapheme rules (practical transcription). Diacritics are stripped; Latin look-alikes inside Cyrillic words are mapped to Cyrillic. |
| Symbols | `& и`, `+ плюс`, `= равно`, `№ номер`, `§ параграф`, `@ собака`, `< > ≤ ≥ ≠ ≈ ± × ÷` spoken. Bare `° $ € £ ¥ ₽` are named. Quotes become `« »`. Spaced or doubled dashes become `—`; a hyphen between letters is kept; a hyphen between a letter and a digit becomes a space. `...` becomes `…`. |
| Abbreviations | т.е., т.к., т.д., т.п., и др., напр., см., рис., табл., ул., стр., тел., обл., им., проф., `г.` before a capitalized word, and bare руб./коп./тыс./млн/млрд/долл. A consumed abbreviation dot is re-emitted when it also ends a sentence: end of text, line break, or a capital letter next. |

## Limits

- Agreement uses only one neighbouring word. Adjectives between a number and its noun, animate accusative, and `по` + number (left nominative) can produce wrong forms.
- English rule transcription is approximate (`Johnson` → `Джонсон`, `Davis` → `Давис`). Add frequent words to `LatinTranslit.WORDS`.
- Abbreviation dots also end sentences in `SpeechPlan.sentences`, so `5 тыс. руб.` split there loses context. Segmenting happens before the normalizer runs.
- Shtorm output alphabet: the 160 tokenizer fixtures prove single-character pieces for Russian letters and `! ( ) , - . : ; ? « » —` plus U+0301. Uppercase Ж Й Ч Ы Ю do not appear in the fixtures and are assumed present. `encodeSpeakable` drops anything that is not a piece, so a wrong assumption costs a letter, not an error.

## Verification

`app/src/test/.../RussianNormalizerTest`, run by `gradlew test`, contains:

- A 148-case input → output table.
- Declension checks and offset-map checks.
- A 20,000-input seeded fuzz in strict mode, so rule bugs throw instead of degrading. It covers random Unicode, surrogates, and web tokens. It asserts output ⊆ the alphabet above and valid source ranges, and runs Silero pieces and, when `tools/models/shtorm/onnx` exists, Shtorm `encodeSpeakable`.

`ShtormBpeCheck` checks that `encodeSpeakable` equals `encode` on the supported fixtures and drops OOV, Latin, and malformed UTF-16.
