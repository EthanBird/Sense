# CC-CEDICT data notice

Sense uses the simplified Chinese and pinyin fields from **CC-CEDICT**, a
community-maintained Chinese-English dictionary published by MDBG.

- Project and download page: https://www.mdbg.net/chinese/dictionary?page=cc-cedict
- Pinned mirror: https://github.com/rhcarvalho/cedict/tree/18b4f1e58ba3fec864bb74ee6965c07fa658bdbc
- License: Creative Commons Attribution-ShareAlike 4.0 International
- Full license text: https://creativecommons.org/licenses/by-sa/4.0/legalcode
- Local license copy: [CC-BY-SA-4.0.txt](CC-BY-SA-4.0.txt)

The generated Sense dictionary normalizes pinyin, selects simplified forms,
removes duplicates, assigns the added entries a low fallback weight, and
combines them with the attributed Rime pinyin-simp source. The resulting
`pinyin_lexicon.bin` and `pinyin_syllables.txt` assets are distributed under
CC BY-SA 4.0. No endorsement by MDBG or the CC-CEDICT contributors is implied.
