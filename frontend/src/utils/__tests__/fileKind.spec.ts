import { describe, it, expect } from 'vitest'
import { classify, extOf, iconFor } from '@/utils/fileKind'

describe('extOf', () => {
  it.each([
    ['photo.PNG', '.png'],
    ['page.html', '.html'],
    ['no-ext', ''],
    ['README', ''],
    ['archive.tar.gz', '.gz'],
    ['.gitignore', ''],
    ['AI_Agent_工具盘点.xlsx', '.xlsx'],
  ])('extOf(%s) = %s', (name, expected) => {
    expect(extOf(name)).toBe(expected)
  })
})

describe('classify', () => {
  it.each([
    ['photo.png', 'image'],
    ['photo.PNG', 'image'],   // 大写扩展名
    ['doc.jpg', 'image'],
    ['anim.gif', 'image'],
    ['photo.webp', 'image'],
    ['icon.ico', 'image'],
    ['logo.bmp', 'image'],
    ['page.html', 'html'],
    ['page.htm', 'html'],
    ['vector.svg', 'svg'],
    ['readme.md', 'markdown'],
    ['readme.markdown', 'markdown'],
    ['README', 'code'],
    ['no.ext', 'code'],
    ['archive.tar.gz', 'code'],   // 取最后一个点
    ['AI_Agent_工具盘点.xlsx', 'code'],
  ])('classify(%s) → kind=%s', (name, expected) => {
    expect(classify(name).kind).toBe(expected)
  })

  it('html 走 iframe sandbox=""', () => {
    const info = classify('a.html')
    expect(info).toEqual({ kind: 'html', useFrame: true, sandbox: '' })
  })

  it('svg 走 iframe sandbox=""', () => {
    const info = classify('a.svg')
    expect(info).toEqual({ kind: 'svg', useFrame: true, sandbox: '' })
  })

  it('image 不走 iframe', () => {
    expect(classify('a.png')).toEqual({ kind: 'image', useFrame: false, sandbox: null })
  })

  it('markdown 不走 iframe', () => {
    expect(classify('a.md')).toEqual({ kind: 'markdown', useFrame: false, sandbox: null })
  })

  it('code 不走 iframe', () => {
    expect(classify('a.ts')).toEqual({ kind: 'code', useFrame: false, sandbox: null })
  })
})

describe('iconFor', () => {
  it.each([
    ['photo.png', false, null, '🖼'],
    ['doc.pdf', false, null, '📕'],
    ['README.md', false, null, '📘'],
    ['script.js', false, null, '⚙️'],
    ['archive.zip', false, null, '📦'],
    ['data.xlsx', false, null, '📊'],
    ['song.mp3', false, null, '🎵'],
    ['movie.mp4', false, null, '🎬'],
    ['index.html', false, null, '🌐'],
    ['config.yaml', false, null, '⚙️'],
    ['README', false, null, '📄'],
    ['unknown.xyz', false, null, '📄'],
  ])('iconFor(%s, dir=false) = %s', (name, isDir, isOpen, expected) => {
    expect(iconFor(name, isDir, isOpen)).toBe(expected)
  })

  it('directory: 📁 closed / 📂 open', () => {
    expect(iconFor('foo', true, false)).toBe('📁')
    expect(iconFor('foo', true, true)).toBe('📂')
  })

  it('unknown isOpen null → 仍按 closed 显示', () => {
    expect(iconFor('foo', true, null)).toBe('📁')
  })
})
