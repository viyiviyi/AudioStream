// 字号与版面的自检：用 CDP 驱动无头 Chrome 真渲染一次，读回每个可见元素的实际计算字号。
// 只在开发时用，不参与产品运行。
//
// 用法：
//   1) 先起程序（Debug 版），再起无头浏览器（独立 profile）：
//      & "C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --disable-gpu `
//        --remote-debugging-port=9222 --user-data-dir=<临时目录> http://127.0.0.1:12570/
//   2) node tools\_ui_font_size.js
//
// 判据：整页最小 14px；只用偶数字号；没有横向溢出。
// 被省略号截断的地方只报告不判失败——那是设计上就允许的（nowrap + ellipsis）。
const CDP = process.argv[2] || 'http://127.0.0.1:9222';
const SHOT = process.argv[3] || '';

async function findPage() {
    const list = await (await fetch(CDP + '/json/list')).json();
    const page = list.find(t => t.type === 'page' && (t.url || '').indexOf('12570') >= 0)
        || list.find(t => t.type === 'page');
    if (!page) throw new Error('找不到可用的页面 target');
    return page;
}

function connect(url) {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(url);
        let id = 0;
        const pending = new Map();
        const events = [];
        ws.addEventListener('open', () => resolve({
            send(method, params) {
                const mid = ++id;
                ws.send(JSON.stringify({ id: mid, method, params: params || {} }));
                return new Promise((res, rej) => pending.set(mid, { res, rej }));
            },
            events,
            close() { ws.close(); }
        }));
        ws.addEventListener('error', () => reject(new Error('WebSocket 连接失败')));
        ws.addEventListener('message', ev => {
            const msg = JSON.parse(ev.data);
            if (msg.id && pending.has(msg.id)) {
                const p = pending.get(msg.id);
                pending.delete(msg.id);
                if (msg.error) p.rej(new Error(JSON.stringify(msg.error)));
                else p.res(msg.result);
            } else if (msg.method) {
                events.push(msg);
            }
        });
    });
}

const PAGE_SCRIPT = `(async () => {
  const steps = [];
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const add = (name, pass, extra) => steps.push({ name: name, pass: !!pass, extra: extra === undefined ? '' : String(extra) });
  const cls = el => {
    const c = el.className;
    if (typeof c === 'string') return c;
    if (c && c.baseVal !== undefined) return c.baseVal;
    return '';
  };
  const nameOf = el => el.tagName.toLowerCase()
      + (el.id ? '#' + el.id : '')
      + (cls(el) ? '.' + String(cls(el)).split(/\\s+/).slice(0, 2).join('.') : '');

  // ---- 1. 逐个可见元素读计算字号 ----
  const sizes = {};
  const small = [];      // 小于 14px
  const odd = [];        // 奇数号
  const all = Array.from(document.querySelectorAll('*'));
  all.forEach(el => {
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden') return;
    if (!el.getClientRects().length) return;
    const fs = parseFloat(cs.fontSize);
    if (!fs) return;
    const key = String(Math.round(fs * 100) / 100);
    sizes[key] = (sizes[key] || 0) + 1;
    const text = (el.textContent || '').trim().slice(0, 18);
    if (fs < 14) small.push(nameOf(el) + '=' + fs + 'px「' + text + '」');
    else if (Math.round(fs) % 2 !== 0) odd.push(nameOf(el) + '=' + fs + 'px「' + text + '」');
  });

  add('页面上没有小于 14px 的字号', small.length === 0,
      small.length ? (small.length + ' 处：' + small.slice(0, 6).join('  ')) : '最小 ' + Math.min.apply(null, Object.keys(sizes).map(Number)) + 'px');
  add('页面上没有奇数大小的字号', odd.length === 0,
      odd.length ? (odd.length + ' 处：' + odd.slice(0, 6).join('  ')) : '没有');
  const allowed = ['14', '16', '18'];
  const extra = Object.keys(sizes).filter(k => allowed.indexOf(k) < 0);
  add('字号只用 14 / 16 / 18 三档', extra.length === 0,
      '实际分布 ' + Object.keys(sizes).sort(function(a,b){return a-b;}).map(k => k + 'px×' + sizes[k]).join('、'));
  add('正文字号就是 14px', getComputedStyle(document.body).fontSize === '14px', getComputedStyle(document.body).fontSize);

  // 输入类控件最容易漏掉：它们不继承 body 的字号，浏览器默认约 13.33px
  const controls = Array.from(document.querySelectorAll('input, select, button, textarea, label'));
  const badCtl = controls.filter(el => {
    if (!el.getClientRects().length) return false;
    const fs = parseFloat(getComputedStyle(el).fontSize);
    return fs < 14 || Math.round(fs) % 2 !== 0;
  }).map(el => nameOf(el) + '=' + parseFloat(getComputedStyle(el).fontSize) + 'px');
  add('输入框、下拉框、按钮的字号也够大', badCtl.length === 0,
      badCtl.length ? (badCtl.length + ' 个：' + badCtl.slice(0, 8).join('  ')) : controls.length + ' 个控件都达标');

  // ---- 2. 版面：不该出现横向溢出 ----
  const de = document.documentElement;
  add('整页没有横向滚动条', de.scrollWidth <= de.clientWidth + 1,
      'scrollWidth=' + de.scrollWidth + ' clientWidth=' + de.clientWidth);

  const overflow = [];
  Array.from(document.querySelectorAll('section, .card, .form-grid, .row-list, table, .field')).forEach(el => {
    if (!el.getClientRects().length) return;
    if (el.scrollWidth > el.clientWidth + 1) overflow.push(nameOf(el) + ' ' + el.scrollWidth + '>' + el.clientWidth);
  });
  add('各个区块没有内容溢出', overflow.length === 0, overflow.slice(0, 6).join('  '));

  // ---- 3. 被省略号截断的地方（只报告） ----
  const clipped = [];
  const clippedNoTitle = [];
  Array.from(document.querySelectorAll('*')).forEach(el => {
    const cs = getComputedStyle(el);
    if (cs.display === 'none') return;
    if (cs.textOverflow !== 'ellipsis') return;
    if (el.scrollWidth > el.clientWidth + 1 && (el.textContent || '').trim()) {
      clipped.push(nameOf(el) + '「' + (el.textContent || '').trim().slice(0, 16) + '」');
      // title 挂在自己身上或者外层容器上都算数（徽标就是把 title 挂在 .badge 上的）
      if (!el.hasAttribute('title') && !el.closest('[title]')) {
        clippedNoTitle.push(nameOf(el) + '「' + (el.textContent || '').trim().slice(0, 16) + '」');
      }
    }
  });
  // 字体加大之后「一行放不下」的地方会变多，但省略号本身不是问题——
  // 前提是完整内容挂在 title 上，鼠标停上去仍然看得到。
  add('被省略号截断的地方都挂了 title', clippedNoTitle.length === 0,
      clippedNoTitle.length ? clippedNoTitle.slice(0, 4).join('  ') : ('共 ' + clipped.length + ' 处，都有 title：' + clipped.slice(0, 3).join('  ')));

  // ---- 4. 关键控件还能用 ----
  add('来源下拉还在', !!document.getElementById('new-remote-device'), '');
  add('播放设备清单还在', !!document.getElementById('new-local-devices'), '');
  add('修改按钮还在', document.querySelectorAll('.edit-btn').length > 0, document.querySelectorAll('.edit-btn').length + ' 个');

  return steps;
})()`;

async function main() {
    const page = await findPage();
    const client = await connect(page.webSocketDebuggerUrl);
    await client.send('Runtime.enable');
    await client.send('Page.enable');
    await client.send('Emulation.setDeviceMetricsOverride', { width: 1500, height: 1000, deviceScaleFactor: 1, mobile: false });
    await client.send('Page.navigate', { url: 'http://127.0.0.1:12570/' });
    await new Promise(r => setTimeout(r, 4500));

    const res = await client.send('Runtime.evaluate', {
        expression: PAGE_SCRIPT,
        awaitPromise: true,
        returnByValue: true
    });
    if (res.exceptionDetails) {
        console.error('页面脚本抛异常：', JSON.stringify(res.exceptionDetails, null, 2));
        process.exit(2);
    }

    const steps = res.result.value || [];
    let failed = 0;
    steps.forEach(s => {
        if (!s.pass) failed++;
        console.log((s.pass ? '通过' : '失败') + ' | ' + s.name + (s.extra ? ' | ' + s.extra : ''));
    });

    if (SHOT) {
        const shot = await client.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
        require('fs').writeFileSync(SHOT, Buffer.from(shot.data, 'base64'));
        console.log('截图已保存：' + SHOT);
    }

    const consoleErrors = client.events
        .filter(e => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error')
        .map(e => (e.params.args || []).map(a => a.value || a.description || '').join(' '));
    const exceptions = client.events
        .filter(e => e.method === 'Runtime.exceptionThrown')
        .map(e => {
            const d = e.params.exceptionDetails || {};
            const ex = d.exception || {};
            const desc = ex.description ? String(ex.description).split('\n')[0] : '';
            return (d.text || '') + (desc ? ('：' + desc) : '');
        });
    if (consoleErrors.length || exceptions.length) {
        console.log('--- 浏览器控制台报错 ---');
        consoleErrors.concat(exceptions).forEach(t => console.log('  ' + t));
        failed += consoleErrors.length + exceptions.length;
    } else {
        console.log('浏览器控制台：没有报错');
    }

    console.log('合计 ' + steps.length + ' 项，失败 ' + failed + ' 项');
    client.close();
    process.exit(failed ? 1 : 0);
}

main().catch(e => { console.error('自检脚本自身出错：', e); process.exit(2); });
