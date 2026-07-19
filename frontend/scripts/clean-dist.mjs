import { mkdir, readdir, rm } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const distDirectory = resolve(projectRoot, 'dist')

await rm(distDirectory, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 })
await mkdir(distDirectory, { recursive: true })

const remainingEntries = await readdir(distDirectory)
if (remainingEntries.length > 0) {
  throw new Error(`Failed to clean ${distDirectory}: ${remainingEntries.join(', ')}`)
}

console.log(`Cleaned build output at ${distDirectory}`)
