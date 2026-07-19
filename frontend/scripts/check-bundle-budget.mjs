import fs from 'node:fs'
import path from 'node:path'

const distDirectory = path.resolve(process.cwd(), 'dist')
const indexPath = path.join(distDirectory, 'index.html')
const initialBudgetBytes = 650 * 1024
const singleInitialChunkBudgetBytes = 250 * 1024
const lazyMermaidBudgetBytes = 3.2 * 1024 * 1024

function fail(message) {
  console.error(`[bundle-budget] FAIL: ${message}`)
  process.exitCode = 1
}

if (!fs.existsSync(indexPath)) {
  fail(`missing build output: ${indexPath}`)
} else {
  const html = fs.readFileSync(indexPath, 'utf8')
  const referencedAssets = [...html.matchAll(/(?:src|href)="([^"?#]+)"/g)]
    .map((match) => match[1])
    .filter((asset) => asset.startsWith('/'))
    .filter((value, index, values) => values.indexOf(value) === index)
  const initialAssets = referencedAssets.filter((asset) => asset.endsWith('.js'))

  for (const asset of referencedAssets) {
    const filePath = path.join(distDirectory, asset.replace(/^\//, ''))
    if (!fs.existsSync(filePath) || fs.statSync(filePath).size === 0) {
      fail(`referenced build asset is missing or empty: ${asset}`)
    }
  }

  if (initialAssets.some((asset) => asset.includes('vendor-mermaid'))) {
    fail('vendor-mermaid must remain lazy and must not be modulepreloaded')
  }

  let initialBytes = 0
  for (const asset of initialAssets) {
    const filePath = path.join(distDirectory, asset.replace(/^\//, ''))
    if (!fs.existsSync(filePath)) {
      fail(`referenced initial asset is missing: ${asset}`)
      continue
    }
    const bytes = fs.statSync(filePath).size
    initialBytes += bytes
    if (bytes > singleInitialChunkBudgetBytes) {
      fail(`${path.basename(asset)} is ${(bytes / 1024).toFixed(1)} KiB; single initial chunk budget is 250 KiB`)
    }
  }

  if (initialBytes > initialBudgetBytes) {
    fail(`initial JavaScript is ${(initialBytes / 1024).toFixed(1)} KiB; budget is 650 KiB`)
  }

  const assetDirectory = path.join(distDirectory, 'assets')
  const entryChunks = fs.readdirSync(assetDirectory)
    .filter((name) => /^index-[\w-]+\.js$/.test(name))
  if (entryChunks.length !== 1) {
    fail(`expected exactly one entry chunk, found ${entryChunks.length}: ${entryChunks.join(', ')}`)
  }

  const mermaidChunk = fs.readdirSync(assetDirectory)
    .find((name) => name.startsWith('vendor-mermaid-') && name.endsWith('.js'))
  if (!mermaidChunk) {
    fail('lazy vendor-mermaid chunk was not produced')
  } else {
    const mermaidBytes = fs.statSync(path.join(assetDirectory, mermaidChunk)).size
    if (mermaidBytes > lazyMermaidBudgetBytes) {
      fail(`lazy Mermaid chunk is ${(mermaidBytes / 1024 / 1024).toFixed(2)} MiB; budget is 3.2 MiB`)
    }
  }

  if (!process.exitCode) {
    console.log(`[bundle-budget] PASS: initial JavaScript ${(initialBytes / 1024).toFixed(1)} KiB; Mermaid remains lazy`)
  }
}
