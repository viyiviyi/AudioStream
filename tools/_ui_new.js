// 新配置页的只读自检：布局、字号、面板打开与关闭、控制台报错。
// 只在开发时用，不参与产品运行。
//
// 与 _ui_click.js 的区别：不写任何数据。编辑面板只打开再取消，
// 日志只展开再收起，不点添加、不点删除、不动授权规则。
//
// 用法（先把无头 Chrome 起在 9223 上并打开页面）：
//   chrome --headless=new --disable-gpu --disable-web-security
//          --remote-debugging-port=9223 --user-data-dir=<临时目录>
//          --window-size=1366,1000 file:///.../web/index.html
//   node tools\_ui_new.js http://127.0.0.1:9223
const CDP = process.argv[2] || 'http://127.0.0.1:9223';

async function findPage() {
    const list = await (await fetch(CDP + '/json/list')).json();
    const page = list.find(t => t.type === 'page' && /index\.html|12570/.test(t.url || ''))
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
  const $ = id => document.getElementById(id);
  const add = (name, pass, extra) => steps.push({ name: name, pass: !!pass, extra: extra === undefined ? '' : String(extra) });
  const waitFor = async (fn, ms) => {
    const until = Date.now() + ms;
    while (Date.now() < until) { if (fn()) return true; await sleep(150); }
    return false;
  };

  await waitFor(() => $('info-machineid') && $('info-machineid').textContent !== '—', 15000);
  add('本机信息已填充', $('info-pcname').textContent !== '—', $('info-pcname').textContent);
  add('拉取列表容器在位', !!$('player-list'));

  // 1. 没有横向溢出：内容区与整页都不该出现横向滚动
  const doc = document.documentElement;
  add('页面没有横向溢出', doc.scrollWidth <= doc.clientWidth + 1,
      'scrollWidth ' + doc.scrollWidth + ' / clientWidth ' + doc.clientWidth);

  // 2. 字号：所有可见文本实测 >= 14px，且不用奇数号
  const sizes = new Set();
  const offenders = [];
  document.querySelectorAll('body *').forEach(el => {
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden') return;
    if (!el.textContent || !el.textContent.trim()) return;
    if (el.children.length > 0) return;           // 只看叶子节点，避免父级继承重复
    const px = parseFloat(style.fontSize);
    if (!px) return;
    sizes.add(px);
    if (px < 14 || Math.round(px) % 2 === 1) offenders.push(el.tagName.toLowerCase() + ':' + px);
  });
  add('字号全部 >=14 且为偶数', offenders.length === 0, offenders.slice(0, 6).join(' / ') || [...sizes].sort((a, b) => a - b).join(', '));

  // 3. 编辑面板：打开 -> 检查控件 -> 取消（不保存，不改配置）
  const editBtn = document.querySelector('.player-row .edit-btn');
  if (editBtn) {
    editBtn.click();
    const open = await waitFor(() => $('edit-panel') && !$('edit-panel').classList.contains('hidden'), 3000);
    add('编辑面板能打开', open);
    add('面板里来源下拉在位', !!$('edit-source-device') && $('edit-source-device').options.length > 0,
        $('edit-source-device') ? $('edit-source-device').options.length + ' 项' : '');
    add('面板里策略下拉在位', !!$('edit-policy'));
    add('面板里目标可勾选', !!$('edit-local-devices') && $('edit-local-devices').querySelectorAll('input[type=checkbox]').length > 0);
    $('edit-cancel-btn').click();
    add('取消后面板关闭', await waitFor(() => $('edit-panel').classList.contains('hidden'), 2000));
  } else {
    add('编辑面板能打开', true, '当前没有拉取记录，跳过');
  }

  // 4. 日志：展开读到内容，再收起
  $('log-toggle').click();
  const logShown = await waitFor(() => $('log-body') && !$('log-body').classList.contains('hidden')
      && $('log-box').textContent.length > 50, 8000);
  add('日志展开后读到内容', logShown, $('log-box').textContent.replace(/\\s+/g, ' ').slice(0, 70));
  $('log-toggle').click();
  add('日志能收起', await waitFor(() => $('log-body').classList.contains('hidden'), 2000));

  // 5. 页面自己没报错
  add('页面无未捕获异常', !window.__uiCheckError, window.__uiCheckError || '');

  return steps;
})()`;

async function main() {
    const page = await findPage();
    const client = await connect(page.webSocketDebuggerUrl);
    await client.send('Runtime.enable');
    await client.send('Page.enable');
    await client.send('Runtime.evaluate', {
        expression: `window.__uiCheckError='';
            window.addEventListener('error', e => { window.__uiCheckError = String(e.message); });
            window.addEventListener('unhandledrejection', e => { window.__uiCheckError = String(e.reason); });`
    });

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

    const consoleErrors = client.events
        .filter(e => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error')
        .map(e => (e.params.args || []).map(a => a.value || a.description || '').join(' '));
    const exceptions = client.events
        .filter(e => e.method === 'Runtime.exceptionThrown')
        .map(e => (e.params.exceptionDetails && e.params.exceptionDetails.text) || '');
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
