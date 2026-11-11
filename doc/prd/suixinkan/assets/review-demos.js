(function () {
  'use strict';
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
  const message = (root, text) => { $('.demo-status', root).textContent = text; };
  $$('[data-list-demo]').forEach(root => {
    let count = 3;
    let mode = 'ready';
    const render = () => {
      $('.demo-count', root).textContent = `${count} 个示例条目`;
      const list = $('.demo-list', root);
      list.replaceChildren(...Array.from({ length: count }, (_, index) => {
        const item = document.createElement('span');
        item.textContent = `示例作品 ${index + 1}`;
        return item;
      }));
      message(root, mode === 'cache' ? '正在展示缓存数据；自动追页已停止。' :
        mode === 'error' ? '来源暂时不可用，已有内容保留。' : '示例列表可浏览。');
    };
    $$('select', root).forEach(select => select.addEventListener('change', () => {
      count = 3; mode = 'ready'; render();
      message(root, `${$$('select', root).map(x => x.selectedOptions[0].textContent).join(' · ')}：从第一页展示。`);
    }));
    $$('[data-action]', root).forEach(button => button.addEventListener('click', () => {
      switch (button.dataset.action) {
        case 'refresh': count = 3; mode = 'ready'; render(); message(root, '刷新成功，替换为第一页，没有重复追加。'); break;
        case 'more': if (mode === 'ready') { count += 3; render(); message(root, '下一页追加完成，重复条目已排除。'); } else message(root, '请先手动刷新恢复；自动追页已停止。'); break;
        case 'cache': mode = 'cache'; render(); break;
        case 'error': mode = 'error'; render(); break;
      }
    }));
    render();
  });
  const search = $('#search-demo');
  if (search) {
    const input = $('input', search);
    const type = $('select', search);
    const run = () => {
      const query = input.value.trim().replace(/[《》]/g, '').replace(/([\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])/g, '$1');
      if (!query || query.length > 160) {
        message(search, '请输入 1–160 字的影视名称。'); return;
      }
      $('.demo-search-result', search).hidden = false;
      $('.demo-search-result', search).textContent = `交互示例：名称“${query}”，类型“${type.selectedOptions[0].textContent}”。此处仅模拟资源结果，不连接影视来源。`;
      message(search, '搜索完成：可选择资源或查看原站。');
    };
    $('form', search).addEventListener('submit', event => { event.preventDefault(); run(); });
    type.addEventListener('change', run);
    $$('[data-action]', search).forEach(button => button.addEventListener('click', () => {
      if (button.dataset.action === 'clear') input.value = '';
      $('.demo-search-result', search).hidden = true;
      message(search, button.dataset.action === 'empty' ? '没有找到资源，请换一个名称搜索。' :
        button.dataset.action === 'error' ? '无法访问影视来源，请检查网络后重试。' :
        button.dataset.action === 'back' ? '已返回榜单，输入名称保留。' : '已清空并返回榜单，旧搜索不能回流。');
    }));
  }
  const player = $('#player-demo');
  if (player) {
    let playing = true;
    let episode = 1;
    let line = '线路 1';
    let currentLine = line;
    let full = false;
    let error = false;
    const position = $('input[type=range]', player);
    $('select[name=rate]', player).value = '1×';
    const stateText = () => error ? '播放失败，请重新解析。' :
      `${playing ? '正在播放' : '已暂停'} · ${currentLine} · 第 ${episode} 集 · ${position.value} 分钟`;
    const render = () => {
      $('.demo-video-title', player).textContent = `示例剧集 · ${currentLine} · 第 ${episode} 集`;
      message(player, stateText());
      $('[data-action=toggle]', player).textContent = playing ? '暂停' : '播放';
      $('[data-action=fullscreen]', player).textContent = full ? '退出全屏示意' : '全屏示意';
      $$('[data-episode]', player).forEach(button => button.setAttribute('aria-pressed', String(Number(button.dataset.episode) === episode && line === currentLine)));
      player.classList.toggle('demo-expanded', full);
    };
    $('select[name=line]', player).addEventListener('change', event => {
      line = event.target.selectedOptions[0].textContent; render();
      message(player, `正在浏览${line}选集；当前视频保持${currentLine}第 ${episode} 集。点集数才切换视频。`);
    });
    $('select[name=rate]', player).addEventListener('change', event => {
      message(player, `${stateText()} · 倍速 ${event.target.value}；暂停状态保持。`);
    });
    position.addEventListener('input', render);
    $$('[data-episode]', player).forEach(button => button.addEventListener('click', () => {
      episode = Number(button.dataset.episode); currentLine = line; position.value = 0; playing = true; error = false; render();
    }));
    $$('[data-action]', player).forEach(button => button.addEventListener('click', () => {
      switch (button.dataset.action) {
        case 'toggle': if (!error) playing = !playing; break;
        case 'refresh': render(); message(player, `信息刷新完成，保留${playing ? '播放' : '暂停'}状态和 ${position.value} 分钟位置。`); return;
        case 'end': if (episode < 3) { episode++; position.value = 0; playing = true; } else { playing = false; render(); message(player, '最后一集已结束，不跨线路；可选择当前集重新播放。'); return; } break;
        case 'error': error = true; playing = false; break;
        case 'retry': error = false; position.value = 0; playing = true; break;
        case 'background': playing = false; break;
        case 'fullscreen': full = !full; break;
        case 'back': if (full) full = false; else { playing = false; render(); message(player, '已离开详情：视频和声音停止，旧请求不再触发播放。'); return; } break;
      }
      render();
    }));
    render();
  }
  if (typeof mermaid !== 'undefined') {
    mermaid.initialize({ startOnLoad: true, theme: 'neutral', securityLevel: 'strict' });
  }
})();
