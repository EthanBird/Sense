"""Small bilingual Python/Kotlin numeric fixture, unrelated to evaluation answers."""
from pathlib import Path
from train_character_lm import train_model,write_model,read_model,BOS,UNK
from train_fourgram_extension import fit,encode,decode


def main():
    root=Path(__file__).resolve().parents[1]/'core-input/src/test/resources/fourgram-lm'
    root.mkdir(parents=True,exist_ok=False)
    texts=['甲乙丙丁']*8+['戊乙丙己']*8+['乙丙丁甲','戊乙丙丁','𠀀乙丙丁']*2
    write_model(train_model(texts,min_char_count=1,min_trigram_count=1),root/'base.scng')
    base=(root/'base.scng').read_bytes();model,_=fit(read_model(base),texts)
    data=encode(model,base);(root/'extension.scq4').write_bytes(data);loaded=decode(data,base)
    rows=['# previous3\tprevious2\tprevious1\tnext\tlower\tfourgram']
    for ctx in [(BOS,BOS,BOS),(BOS,ord('甲'),ord('乙')),tuple(map(ord,'甲乙丙')),tuple(map(ord,'戊乙丙')),
                tuple(map(ord,'𠀀乙丙')),(UNK,ord('乙'),ord('丙')),tuple(map(ord,'丁己甲'))]:
        for w in sorted(loaded.base.unigrams):
            rows.append('\t'.join(map(str,[*ctx,w,loaded.base.log_probability(ctx[1],ctx[2],w),loaded.log_probability(*ctx,w)])))
    (root/'scores.tsv').write_text('\n'.join(rows)+'\n',encoding='utf-8')
    print('Created lower/extension/numeric fixture with',len(rows)-1,'transitions')


if __name__=='__main__':main()
