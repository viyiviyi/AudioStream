// 编辑面板「改来源设备」的自检：用 CDP 驱动无头 Chrome 真改一次来源、真保存。
// 只在开发时用，不参与产品运行。
//
// 用法：
//   1) 先起程序（Debug 版），再起无头浏览器（独立 profile）：
//      & "C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --disable-gpu `
//        --remote-debugging-port=9222 --user-data-dir=<临时目录> http://127.0.0.1:12570/
//   2) node tools\_ui_edit_source.js
//
// 这个脚本会真的建一条本机流转的拉取（来源 = 系统默认输入设备，播放目标挑一块虚拟声卡），
// 再把它改成系统默认输出设备，确认改完落盘、界面留得住之后立刻删掉，不留痕迹。
// 之所以敢真建：两边都是虚拟线，采的是虚拟输入、放的是虚拟输出，不会有声音从真实喇叭出来。
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
    while (Date.now() < until) { if (await fn()) return true; await sleep(200); }
    return false;
  };
  const players = async () => ((await (await fetch('/api/players')).json()).Result || []);
  const boxes = () => Array.from($('new-local-devices').querySelectorAll('input[type=checkbox]'));
  const editSelect = () => $('edit-source-device');

  const select = $('new-remote-device');
  await waitFor(() => select.options.length > 0, 15000);

  // ---- 1. 先建一条本机流转的拉取，拿它当被编辑的对象 ----
  select.value = 'default-input';
  const ready = await waitFor(() => boxes().length > 0, 15000);
  add('播放设备列表已加载', ready, boxes().length + ' 个候选');
  const target = boxes().find(b => (b.dataset.name || '').indexOf('Virtual Speakers') >= 0)
      || boxes().find(b => b.value === 'default')
      || boxes()[0];
  add('找到可勾选的播放设备', !!target, target ? (target.dataset.name + '（' + target.value + '）') : '');
  if (target) target.click();
  await sleep(200);

  const beforeIds = new Set((await players()).map(p => p.ID));
  $('add-player-btn').click();
  const created = await waitFor(async () => (await players()).some(p => !beforeIds.has(p.ID)), 15000);
  // 只认这次新加出来的那一条：按来源设备号去找会在机器上已有同样来源的记录时认错，
  // 最后删掉的是用户自己的记录（这个坑真踩过一次）。
  let mine = (await players()).find(p => !beforeIds.has(p.ID));
  add('先建起一条来源是「系统默认输入设备」的拉取', created && !!mine, mine ? mine.ID : '没建起来');
  if (!mine) { add('没有可编辑的对象，后面跳过', false, ''); return steps; }

  // ---- 2. 打开修改面板，看来源下拉 ----
  const foundEdit = await waitFor(() => !!document.querySelector('.edit-btn[data-id="' + mine.ID + '"]'), 15000);
  const editBtn = document.querySelector('.edit-btn[data-id="' + mine.ID + '"]');
  add('列表里有这一条的「修改」按钮', foundEdit && !!editBtn, editBtn ? editBtn.textContent.trim() : '没找到');
  if (editBtn) editBtn.click();
  const opened = await waitFor(() => editSelect() && editSelect().options.length > 0, 15000);
  add('修改面板里有来源设备下拉', !!editSelect(), editSelect() ? (editSelect().options.length + ' 个选项') : '没有这个下拉');
  if (!opened) { return steps; }

  // 版面：播放策略要另起一行，不和来源设备挤在一起；那句解释来源地址的话不要再出现
  const srcBox = editSelect().getBoundingClientRect();
  const polBox = $('edit-policy').getBoundingClientRect();
  add('播放策略另起一行（不与来源设备并排）', polBox.top >= srcBox.bottom - 1,
      '来源底部=' + Math.round(srcBox.bottom) + '  策略顶部=' + Math.round(polBox.top));
  const panelText = (document.querySelector('.edit-panel') || {}).textContent || '';
  add('编辑面板里没有那句多余的来源说明', panelText.indexOf('来源地址不变') < 0, panelText.slice(0, 80));

  const groups = Array.from(editSelect().querySelectorAll('optgroup'));
  add('编辑面板里没有单独的「跟随系统默认」分组', !groups.some(g => (g.label || '').indexOf('跟随系统默认') >= 0),
      groups.map(g => g.label).join(' / '));
  const editOuts = groups.find(g => (g.label || '').indexOf('输出设备') === 0);
  const editIns = groups.find(g => (g.label || '').indexOf('输入设备') === 0);
  const editOutFirst = editOuts ? editOuts.querySelector('option') : null;
  const editInFirst = editIns ? editIns.querySelector('option') : null;
  add('编辑面板里能选系统默认输出设备（输出分组第一条）', !!editOutFirst && editOutFirst.value === 'default',
      editOutFirst ? editOutFirst.value : '没找到');
  add('编辑面板里能选系统默认输入设备（输入分组第一条）', !!editInFirst && editInFirst.value === 'default-input',
      editInFirst ? editInFirst.value : '没找到');
  add('当前选中的就是这条记录原来的来源', editSelect().value === 'default-input',
      '下拉选中=' + editSelect().value + '  记录里=' + mine.SourceDeviceID);

  // ---- 3. 真改一次：输入设备 → 输出设备 ----
  editSelect().value = 'default';
  editSelect().dispatchEvent(new Event('change', { bubbles: true }));
  await sleep(200);
  const saveBtn = $('edit-save-btn');
  add('有保存按钮', !!saveBtn, saveBtn ? saveBtn.textContent.trim() : '');
  saveBtn.click();
  // 面板收起是异步的（要等保存请求回来才会 closeEdit），必须等它。
  // 原来这里点完就同步查面板，会偶发失败在「面板还没收起来」上，看着像保存没生效。
  const closed = await waitFor(() => !editSelect(), 10000);

  const changed = await waitFor(async () => {
    const p = (await players()).find(x => x.ID === mine.ID);
    return p && p.SourceDeviceID === 'default';
  }, 15000);
  const after = (await players()).find(x => x.ID === mine.ID);
  add('保存后来源设备真的换了', changed, after ? after.SourceDeviceID : '记录没了');
  add('来源设备名跟着换了', !!after && after.SourceDeviceName === '系统默认输出设备',
      after ? String(after.SourceDeviceName) : '');
  add('来源地址没有被动过（这条本来就是本机流转）', !!after && !after.IP, after ? JSON.stringify(after.IP) : '');
  add('播放目标没有被弄丢', !!after && (after.Targets || []).length === 1,
      after ? ('目标 ' + (after.Targets || []).length + ' 个') : '');
  const toastText = (($('toast-wrap') || {}).textContent || '').trim();
  add('保存后页面提示的是已保存', toastText.indexOf('已保存') >= 0, toastText || '（没有提示）');
  add('修改面板已经收起来', closed, closed ? '' : '还开着');

  // ---- 4. 再打开一次，看下拉记不记得新来源 ----
  await waitFor(() => !!document.querySelector('.edit-btn[data-id="' + mine.ID + '"]'), 10000);
  const editBtn2 = document.querySelector('.edit-btn[data-id="' + mine.ID + '"]');
  if (editBtn2) editBtn2.click();
  const reopened = await waitFor(() => editSelect() && editSelect().options.length > 0, 15000);
  add('重新打开修改面板时选中新的来源', reopened && editSelect().value === 'default',
      reopened ? editSelect().value : '面板没打开');
  if ($('edit-cancel-btn')) $('edit-cancel-btn').click();

  // ---- 收尾：删掉刚才那条，别在用户机器上留东西 ----
  await fetch('/api/del?id=' + encodeURIComponent(mine.ID), { method: 'POST' });
  const cleaned = await waitFor(async () => !(await players()).some(x => x.ID === mine.ID), 10000);
  add('收尾把测试拉取删干净了', cleaned, '剩余拉取 ' + (await players()).length + ' 条');

  add('页面无未捕获异常', !window.__uiEditSourceError, window.__uiEditSourceError || '');
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
        expression: `window.__uiEditSourceError='';
            window.addEventListener('error', e => { window.__uiEditSourceError = String(e.message); });
            window.addEventListener('unhandledrejection', e => { window.__uiEditSourceError = String(e.reason); });`
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
