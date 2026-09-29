// 配置页静态自检：内联脚本语法、外链依赖、JS 引用的 DOM id 是否都存在。
// 只在开发时用，不参与产品运行。
const fs = require('fs');
const html = fs.readFileSync(process.argv[2], 'utf8');
let failed = false;

// 1. 内联脚本块语法
const scripts = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)];
console.log('内联脚本块：' + scripts.length);
scripts.forEach((m, i) => {
    try {
        new Function(m[1]);
        console.log('  块 ' + i + '：语法通过（' + m[1].split('\n').length + ' 行）');
    } catch (e) {
        failed = true;
        console.log('  块 ' + i + '：语法错误 -> ' + e.message);
    }
});

// 2. 外链依赖：真正会让浏览器去网络上取的只有 script src、link href、@import。
//    xmlns 是命名空间，JS 里的 http://127.0.0.1 是本机接口地址，都不算外链。
const externals = [
    ...html.matchAll(/<script[^>]*\bsrc\s*=\s*["']([^"']+)["']/gi),
    ...html.matchAll(/<link[^>]*\bhref\s*=\s*["']([^"']+)["']/gi),
    ...html.matchAll(/@import\s+url\(\s*["']?([^"')]+)/gi)
].map(m => m[1]).filter(u => /^(https?:)?\/\//i.test(u));
console.log('外链资源引用（script src / link href / @import）：' + externals.length);
if (externals.length) console.log('  ' + externals.join('\n  '));
const urls = [...new Set([...html.matchAll(/https?:\/\/[^\s"'<>)]+/gi)].map(m => m[0]))];
console.log('页面里出现的 http(s) 地址（含命名空间与代码常量）：' + JSON.stringify(urls));
if (externals.length > 0) { failed = true; console.log('  ^ 还有外链，离线打开会缺资源'); }

// 3. DOM id 引用
const ids = new Set([...html.matchAll(/\sid\s*=\s*"([^"]+)"/g)].map(m => m[1]));
const used = new Set([...html.matchAll(/\$\('([^']+)'\)/g)].map(m => m[1]));
const missing = [...used].filter(id => !ids.has(id));
console.log('HTML 中的 id：' + ids.size + ' 个；JS 通过 $() 取用：' + used.size + ' 个');
if (missing.length) { failed = true; console.log('  JS 取了不存在的 id：' + missing.join('、')); }
else { console.log('  JS 取用的 id 全部存在'); }

// 4. JS 里引用的图标 symbol
const symbols = new Set([...html.matchAll(/<symbol id="([^"]+)"/g)].map(m => m[1]));
const usedIcons = new Set([...html.matchAll(/#(i-[a-z-]+)/g)].map(m => m[1]));
const missingIcons = [...usedIcons].filter(id => !symbols.has(id));
console.log('图标 symbol：定义 ' + symbols.size + ' 个，引用 ' + usedIcons.size + ' 个');
if (missingIcons.length) { failed = true; console.log('  引用了没定义的图标：' + missingIcons.join('、')); }
else { console.log('  引用到的图标全部有定义'); }

console.log(failed ? '结果：有问题' : '结果：全部通过');
process.exitCode = failed ? 1 : 0;
