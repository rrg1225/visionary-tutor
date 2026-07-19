import { cp, mkdir, rm } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const source = resolve(projectRoot, 'node_modules', 'pyodide')
const target = resolve(projectRoot, 'public', 'vendor', 'pyodide', '0.26.4')

await rm(target, { recursive: true, force: true })
await mkdir(target, { recursive: true })
await cp(source, target, {
  recursive: true,
  filter(path) {
    const normalized = path.replaceAll('\\', '/')
    return !normalized.includes('/node_modules/')
      && !normalized.endsWith('/package.json')
      && !normalized.endsWith('/README.md')
  },
})

console.log(`Prepared self-hosted Pyodide assets at ${target}`)
