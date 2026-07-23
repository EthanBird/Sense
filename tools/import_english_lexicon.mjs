#!/usr/bin/env node

import {writeFile} from 'node:fs/promises'
import {pathToFileURL} from 'node:url'

const [, , source, output, requestedCount = '20000'] = process.argv
if (!source || !output) {
  throw new Error('usage: import_english_lexicon.mjs <popular-english-words/words.js> <output> [count]')
}

const count = Number.parseInt(requestedCount, 10)
if (!Number.isInteger(count) || count <= 0) throw new Error(`invalid count: ${requestedCount}`)

const imported = await import(pathToFileURL(source).href)
const sourceWords = imported.words
if (!Array.isArray(sourceWords)) throw new Error('source module must export a words array')

const excluded = new Set([
  'nbsp',
  'mdash',
  'bgcolor',
  'rowspan',
  'colspan',
  'pagename',
  'username',
  'wikipedia',
])
const unique = new Set()
for (const value of sourceWords) {
  if (!/^[a-z]{1,32}$/.test(value) || excluded.has(value)) continue
  if (value.length === 1 && value !== 'a' && value !== 'i') continue
  unique.add(value)
  if (unique.size === count) break
}
if (unique.size !== count) throw new Error(`only found ${unique.size} eligible words`)

const header = [
  '# popular-english-words 1.0.2, first 20,000 eligible popularity-ranked words',
  '# https://github.com/tkoop/popular-english-words',
  '# ISC license; see assets/POPULAR-ENGLISH-WORDS-ISC.txt',
]
await writeFile(output, `${header.join('\n')}\n${[...unique].join('\n')}\n`, 'utf8')
