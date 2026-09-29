// 播放设备列表「刷新设备」按钮的自检：用 CDP 驱动无头 Chrome 真点一遍。
// 只在开发时用，不参与产品运行。
//
// 用法：
//   1) 先起程序（Debug 版），再起无头浏览器（独立 profile）：
//      & "C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --disable-gpu `
//        --remote-debugging-port=9222 --user-data-dir=<临时目录> http://127.0.0.1:12570/
//   2) node tools\_ui_device_refresh.js
//
// 这个脚本不依赖任何预置的 players.json：直接在「添加一条拉取」的设备多选列表上操作，
// 勾一个虚拟声卡、点刷新、看勾选还在不在。全程不点「添加」，不会开出任何声卡。
const CDP = process.argv[2] || 'http://127.0.0.1:9222';

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
  const $ = id => document.getElementById(id);
  const add = (name, pass, extra) => steps.push({ name: name, pass: !!pass, extra: extra === undefined ? '' : String(extra) });
  const waitFor = async (fn, ms) => {
    const until = Date.now() + ms;
    while (Date.now() < until) { if (fn()) return true; await sleep(150); }
    return false;
  };
  const boxes = () => Array.from($('new-local-devices').querySelectorAll('input[type=checkbox]'));
  const checked = () => boxes().filter(b => b.checked);

  add('刷新按钮存在', !!$('local-devices-refresh'));
  const ready = await waitFor(() => boxes().length > 1, 15000);
  add('设备列表已加载', ready, boxes().length + ' 个候选');

  // 挑一个虚拟声卡当勾选对象：它一定在，且不会碰真实发声设备
  const virtual = boxes().find(b => (b.dataset.name || '').indexOf('Virtual Speakers') >= 0) || boxes()[1];
  add('找到可勾选的播放设备', !!virtual, virtual ? virtual.dataset.name : '');
  virtual.click();
  await sleep(200);
  const listBefore = boxes().length;
  add('勾选后顺序行显示已选', checked().length === 1 && $('new-target-order').textContent.indexOf('已选顺序') >= 0,
      checked().length + ' 个勾选 / ' + $('new-target-order').textContent.trim());

  // 点刷新：重新枚举本机端点
  $('local-devices-refresh').click();
  await sleep(2000);
  add('刷新后设备列表还在', boxes().length === listBefore, boxes().length + ' 个候选（刷新前 ' + listBefore + '）');
  add('刷新后已勾选的设备没被刷掉', checked().length === 1,
      '勾选 ' + checked().length + ' 个：' + checked().map(b => b.dataset.name).join('、'));
  add('刷新后勾选顺序仍在', $('new-target-order').textContent.indexOf('已选顺序') >= 0, $('new-target-order').textContent.trim());
  add('刷新按钮已恢复可用', !$('local-devices-refresh').disabled);

  // 取消勾选，确认顺序提示回到「还没选」
  checked().forEach(b => b.click());
  await sleep(200);
  add('取消勾选后顺序提示回到未选', $('new-target-order').textContent.indexOf('还没选') >= 0,
      $('new-target-order').textContent.trim());

  add('页面无未捕获异常', !window.__uiRefreshError, window.__uiRefreshError || '');
  return steps;
})()`;

async function main() {
    const page = await findPage();
    const client = await connect(page.webSocketDebuggerUrl);
    await client.send('Runtime.enable');
    await client.send('Page.enable');
    await client.send('Page.navigate', { url: 'http://127.0.0.1:12570/' });
    await new Promise(r => setTimeout(r, 4000));
    await client.send('Runtime.evaluate', {
        expression: `window.__uiRefreshError='';
            window.addEventListener('error', e => { window.__uiRefreshError = String(e.message); });
            window.addEventListener('unhandledrejection', e => { window.__uiRefreshError = String(e.reason); });`
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
