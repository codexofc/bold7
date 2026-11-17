# RIM's YK compression

A description of the compressed format of the BlackBerry 9900's help pages (`*.html.ykz`) and of
their shared dictionary (`sidedata.data`), as Bold7 reads them (`core/firmware/.../Yk.kt`). The
same format carries other files of BlackBerry OS 7 (`net_rim_bb_email_compression.cod`) and BIS's
`x-rim` HTTP encodings `yk` and `yk-mixed`; only what the help needs is described here.

**Where this comes from.** No public description of RIM's bit stream could be found.
RIM's decoder is native code of the 9900's Java VM (`net.rim.device.internal.compress.YKDecode`),
present in the `Jvm.dll` of the official BlackBerry Simulator 7.1 for the 9900. Its x86 code was
disassembled, then run in an emulator on the help of the 9900 to compare against; this text
describes the behaviour, in our own words, with none of RIM's code. A decoder written from it
gives the same bytes as RIM's on all 1,173 help pages, and RIM's decoder reads streams made by an
encoder written from it.

The method is Yang and Kieffer's grammar-based compression (E.-H. Yang and J. C. Kieffer,
"Efficient universal lossless data compression algorithms based on a greedy sequential grammar
transform", IEEE Trans. Inf. Theory 46(3), 2000), its improved sequential algorithm, with an
arithmetic coder that is Michael Schindler's range coder (1998).

## 1. Framing

A file is a run of blocks, each a 4-byte header and its data:

| byte 0 | bytes 1-3 | data |
|---|---|---|
| `0x84` | length, 24-bit big-endian | YK data |
| `0x83` | 0 | none: end of the file |

Bit 7 of byte 0 is set in every block the help has; its low bits are the kind. RIM's decoder also
knows `0x81` (YK data under another context number), `0x80` (stored bytes) and `0x82` (a context
map, §7); the help has none of them. **The blocks of a file are one stream**: their data is read as one byte string, the
block boundaries mean nothing to the decoder (RIM's splits the side data in 1,020-byte blocks, a
long page in several).

## 2. Side data and pages

`sidedata.data` is itself a YK stream, of about 1.9 MB of help HTML. Decoding it builds a grammar,
and its text is thrown away. A page is then decoded against that grammar, which it does not
change: only the adaptive models move (§4), each page starting from the models the side data left.
So a reader builds the grammar once and keeps a copy of the models per page.

The help of the 9900 in British English (`net_rim_bb_help_9900_series__en_GB.cod`): 103,346 bytes
of side data define 13,454 symbols beyond the bytes; its start rule ends full (65,535 entries).

## 3. Range decoder

State: `low` and `range`, 32-bit unsigned, and `carry`, the last byte read. Start: `carry` = first
byte, `low = carry >> 1`, `range = 0x80`, then refill. **Refill**, while `range <= 2^23`:
`low = (low << 8 | (carry & 1) << 7) mod 2^32`, `carry` = next byte (zero past the end),
`low |= carry >> 1`, `range <<= 8`. Every decision below ends with a refill.

- **Adaptive bit** with counts `c0`, `c1`: `split = (range / (c0 + c1)) * c0` (integer division).
  If `low < split`: bit 0, `range = split`, `c0 += 1`. Else bit 1, `low -= split`, `range -= split`,
  `c1 += 1`. Then if `c0 + c1 > 254`, both become `(c + 1) >> 1`. All counts start at 1.
- **Flat bit**: the same with `split = range >> 1` and nothing learnt.
- **Cumulative table** `t[0..]`: `t[0]` the total, `t[k]` (k ≥ 1) the count of the symbols k and
  above, ending with a 0. `unit = range / t[0]`, `v = low / unit`; walk k from 1 while `v < t[k]`.
  The symbol is `k - 1`, read from the last `t[k]` passed (`above`, or `t[0]` if none) and the first
  not passed (`from`): `low -= unit * from`; `range = unit * above` unless `above` is the total (symbol
  0 keeps the rounding at the top); `range -= unit * from`.
- **Uniform choice** among n: the cumulative table `t[k] = n - k`.

## 4. Models

- **Repeat bits**: 64 adaptive bits, chosen by the last six repeat bits (§5), oldest first in a
  byte of history shifted left, newest in bit 0.
- **The alphabet**: one adaptive model over all symbols, whose number grows. A symbol is its index:
  0 to 255 the bytes, 256 the escape, then each new symbol in order of creation. Symbols go in
  groups of 16, each with a 17-entry cumulative table; the groups are chosen by an implicit binary
  tree of adaptive bits.
  - *Adding* a symbol at place `j` of its group (`j = count mod 16`): `j = 0` opens a group whose
    table is `[1, 0, …, 0]`, and, if it is not the first group, a tree node with counts (1, 1).
    Else `t[1..j]` and `t[0]` each go up by one (the new symbol has count 1); if `t[0]` reaches
    0x3fff, every count up to the first zero becomes `(t + 1) >> 1`.
  - *Decoding*: with G groups, the tree has G - 1 nodes and depth `d` = bit length of G - 1. Start at
    node `2^(d-1) - 1`, read a bit into the group number; then at each of the d - 1 levels move left
    (minus `2^(level - 1)`) after a 0, right after a 1, and read the next bit. A node past the last
    does not exist: its bit is 0 without reading, and the walk goes left. Then the symbol in the
    group by its cumulative table, learning: `t[1..s]` and `t[0]` go up by one. If `t[0]` reaches
    0x3fff, the table is halved from its last non-zero entry down to `t[0]`: each becomes
    `(t + 1) >> 1` plus how many entries after it were raised, and is raised by one more if it
    then equals the next.
- **Tail bits**: one adaptive bit for the 16-bit length of §6.

## 5. Grammar

Rules are lists of symbols. The **start rule** begins with two symbols written raw, before the
range coder, as two 16-bit big-endian values below 256 (`00 3c 00 21`, "<!", for the help). Each
other symbol beyond the escape has a rule, its expansion the concatenation of its symbols'. When a
pair inside a rule is replaced by a new symbol, its other positions become **holes**, skipped by
every walk, so positions never move.

Every symbol x has a **follower list**, entries `(y, rule, position)`: the pair x y is in `rule`
(the start rule written 0xffff) with x at `position`. New entries go at the end, a removed entry
closes the gap: the order matters, it is what §6 counts in. The pair formed by the last two symbols
of the start rule is never in a list until a third arrives. A rule of two symbols has no entries.

Decoding the side data, phrase by phrase:

1. Read a **repeat bit** (§4).
2. **1 after a 0**: a repeat starts. x is the last symbol of the start rule; choose uniformly among
   its followers. The entry chosen gives where the first copy is (rule R, position p of x). A new
   symbol N is created now (added to the alphabet, no rule yet); the repeat has length 2 and ends at
   the next symbol after p in R.
3. **1 after a 1**: the repeat grows by the next symbol of R. It may not reach the last symbol of
   the start rule (the second copy is being read), nor cover a whole rule.
4. **0**: a symbol s from the alphabet.
   - If the previous bit was 1, the repeat ends first: N's rule is the repeated symbols, and it
     replaces both copies (below).
   - Then s is appended to the start rule; the pair the start rule's last two symbols formed before
     it is recorded in the follower list of the first (the pair of the last and s must not be in a
     list already: it would have been a repeat).
   - **Escape** (256): a count of flat 1 bits before a 0, at most 5. 4 ends the side data (the
     newest repeat bit is dropped from the history); 0 ends a page (§6); 3, then a symbol from the
     alphabet above 256, is "send this symbol"; 1, 2 and new bytes are not used by the help.

**Replacing a repeat** of n symbols by N, the first copy at position p of rule R:

1. The pair entry of the copy's first pair is found in its first symbol's list: the last entry with
   the right second symbol, or its neighbour when its position is past p (or before it).
2. If p is not the first position, the pair (symbol before, x) now reads (symbol before, N).
3. For n = 2 the entry of the copy's pair is removed. For more, it now points into N's rule at 0,
   and each inner pair's entry at N, i, its first symbol a hole in R.
4. If the copy ends R, R ends with N: its length becomes p + 1 (and if only one symbol is left
   before N, that pair's entry goes). Else the pair (last of the copy, next) is removed and the last
   of the copy becomes a hole, and the pair (N, next) is added to N's list, unless R is now just
   N and next. When the start rule overlaps itself (a run like "aaa" ending the start rule), no
   such pair exists and the start rule is cut after p + 1.
5. N takes position p in R and the last place of the start rule (its last symbol was x, the second
   copy's first).

## 6. A page

The grammar is fixed; the models start from the side data's (repeat bits, alphabet tables and tree,
tail bit), the history of repeat bits and the previous symbol too (the side data leaves 4 there).
Phrase by phrase: a repeat bit; 0 gives a symbol from the alphabet (an escape with count 0 ends the
loop); 1 gives a symbol chosen uniformly among the previous symbol's followers. The symbol's
expansion is written out and it becomes the previous symbol. After the loop, 16 tail bits (most
significant first) give a length L; if L is not 0, a symbol from the alphabet, of which only the
first L bytes are written.

## 7. Not used by the help

A context map (`0x82` blocks) splits the alphabet into groups chosen by the last byte written; the
help has none, so there is one alphabet. The decoder can also escape to new bytes or switch modes on
flags; none of these occur in the help of the OS Loader, and a reader may refuse them.

Side data may also end like a page (escape count 0, then the 16 tail bits) instead of with count 4.
The grammar then stays open: each page keeps growing it as the side data did (repeats, new
symbols), from a copy of the grammar the side data left, and writes out each phrase it reads. The
help of the BlackBerry Simulator 7.1.0.318 for the 9900 (a different edition, 68,610 bytes of side
data) is so; Bold7 does not read that mode.
