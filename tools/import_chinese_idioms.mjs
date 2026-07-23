#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const [packageRoot, syllablesPath, outputPath] = process.argv.slice(2);
if (!packageRoot || !syllablesPath || !outputPath) {
  console.error(
    "Usage: import_chinese_idioms.mjs <package root> <pinyin_syllables.txt> <output.tsv>",
  );
  process.exit(2);
}

const metadata = JSON.parse(fs.readFileSync(path.join(packageRoot, "package.json"), "utf8"));
if (metadata.name !== "chinese-idiom-chengyu" || metadata.version !== "1.1.0") {
  throw new Error(`Expected chinese-idiom-chengyu 1.1.0, got ${metadata.name} ${metadata.version}`);
}

const sourcePath = path.join(packageRoot, "src", "data", "WordPinyinPairs.json");
const source = JSON.parse(fs.readFileSync(sourcePath, "utf8"));
const sourceEntries = Object.entries(source);
if (sourceEntries.length !== 30_895) {
  throw new Error(`Expected 30895 source entries, got ${sourceEntries.length}`);
}
const acceptedSyllables = new Set(
  fs.readFileSync(syllablesPath, "utf8").trim().split(/\s+/u),
);
// üe is typed as ve by Sense's v-based spelling normalization.
acceptedSyllables.add("lve");
acceptedSyllables.add("nve");

function normalizeSyllable(value) {
  const umlautNormalized = value
    .toLowerCase()
    .replaceAll("u:", "v")
    .replace(/[üǖǘǚǜ]/gu, "v");
  return umlautNormalized
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .replace(/[^a-zv]/gu, "");
}

function isHanText(value) {
  return [...value].every((character) => {
    const codePoint = character.codePointAt(0);
    return (
      (codePoint >= 0x3400 && codePoint <= 0x4dbf) ||
      (codePoint >= 0x4e00 && codePoint <= 0x9fff) ||
      (codePoint >= 0xf900 && codePoint <= 0xfaff)
    );
  });
}

const entries = sourceEntries
  .map(([word, rawPinyin]) => {
    const syllables = rawPinyin
      .trim()
      .split(/\s+/u)
      .map(normalizeSyllable)
      .filter(Boolean);
    return { word, syllables };
  })
  .filter(({ word, syllables }) => (
    word.length > 0 &&
    isHanText(word) &&
    [...word].length === syllables.length &&
    syllables.every((syllable) => acceptedSyllables.has(syllable))
  ))
  .sort((left, right) => left.word < right.word ? -1 : left.word > right.word ? 1 : 0);

if (entries.length !== 30_246) {
  throw new Error(`Expected 30246 aligned Han entries, got ${entries.length}`);
}

const lines = [
  "# Generated from chinese-idiom-chengyu 1.1.0 WordPinyinPairs.json.",
  "# Format: text<TAB>pinyin<TAB>weight<TAB>source-tier",
  "# Tier 1 keeps dictionary-only idioms behind primary conversational words.",
  ...entries.map(({ word, syllables }) => `${word}\t${syllables.join(" ")}\t1\t1`),
  "",
];
fs.writeFileSync(outputPath, lines.join("\n"), "utf8");
console.log(`Wrote ${entries.length} idiom entries to ${outputPath}`);
