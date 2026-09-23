(() => {
  'use strict';
  let editor, selection;
  const byId = id => document.getElementById(id);
  const editable = () => document.querySelector('[contenteditable="true"].sun-editor-editable') || document.querySelector('.sun-editor [contenteditable="true"]');
  function saveSelection() {
    const s = window.getSelection(), root = editable();
    if (root && s.rangeCount && root.contains(s.anchorNode)) selection = s.getRangeAt(0).cloneRange();
  }
  function restoreSelection() {
    const root = editable();
    if (!root) return;
    root.focus();
    if (selection && root.contains(selection.startContainer)) {
      const s = window.getSelection(); s.removeAllRanges(); s.addRange(selection);
    }
  }
  function changed() { if (editor) ScholixNative.changed(editor.$.html.get()); }
  function insert(html) { restoreSelection(); editor.$.html.insert(html); changed(); }
  function theme(config) {
    const root = document.documentElement.style;
    root.setProperty('--surface', config.background); root.setProperty('--foreground', config.foreground);
    root.setProperty('--primary', config.primary); root.setProperty('--container', config.container);
    root.setProperty('--outline', config.outline || config.primary);
    root.setProperty('--muted', config.muted || config.foreground);
    root.setProperty('--selected', config.selected || config.container);
    root.setProperty('--on-selected', config.onSelected || config.foreground);
  }
  function init(config) {
    theme(config);
    if (editor) return;
    editor = SUNEDITOR.create(byId('editor'), {
      plugins: SUNEDITOR.plugins,
      lang: SUNEDITOR_LANG.he, width: '100%', height: '280px', minHeight: '240px',
      textDirection: 'rtl', stickyToolbar: false,
      placeholder: 'Write a message…',
      buttonList: [
        ['undo', 'redo', 'removeFormat'], ['font', 'fontSize', 'blockStyle'],
        ['bold', 'italic', 'underline', 'strike', 'subscript', 'superscript'],
        ['fontColor', 'backgroundColor'], ['align', 'outdent', 'indent', 'lineHeight'],
        ['list', 'blockquote', 'hr'], ['link', 'image', 'table', 'math', 'drawing'],
        ['showBlocks', 'codeView']
      ],
      image: { createFileInput: false },
      externalLibs: { katex: { src: { renderToString: (expression, options) => katex.renderToString(expression, {...options, output: 'mathml', trust: false}) } } },
      events: { onChange: ({data}) => ScholixNative.changed(data) }
    });
    editor.$.html.set(config.html || '<p><br></p>');
    editable().setAttribute('role', 'textbox');
    editable().setAttribute('aria-label', 'Message body');
    editable().setAttribute('aria-multiline', 'true');
    document.addEventListener('selectionchange', saveSelection);
  }
  window.Scholix = {init, theme, content: () => editor ? editor.$.html.get() : '', insert,
    image: url => { const img = document.createElement('img'); img.src = url; img.alt = ''; img.style.maxWidth = '100%'; insert(img.outerHTML); }
  };
  byId('format').onclick = () => { const open = byId('editor-shell').classList.toggle('compact'); byId('format').setAttribute('aria-expanded', String(!open)); };
  byId('more').onclick = () => { const tools = byId('extra-tools'); tools.hidden = !tools.hidden; byId('more').setAttribute('aria-expanded', String(!tools.hidden)); };
  byId('image').onclick = () => ScholixNative.pickImage();
  ['rtl','ltr'].forEach(direction => byId(direction).onclick = () => {
    restoreSelection(); const s = window.getSelection(), root = editable();
    const element = s.anchorNode?.nodeType === Node.ELEMENT_NODE ? s.anchorNode : s.anchorNode?.parentElement;
    const block = element?.closest('p,div,h1,h2,h3,h4,h5,h6,li,td,th,blockquote');
    if (root && block && root.contains(block)) { block.dir = direction; changed(); }
  });
  const symbols = 'α β γ δ ε θ λ μ π σ φ ψ ω Γ Δ Θ Λ Π Σ Φ Ψ Ω ± × ÷ ≠ ≈ ≤ ≥ ∞ √ ∑ ∏ ∫ ∂ ∆ ∈ ∉ ∪ ∩ ∅ ⊂ ⊆ → ← ↔ ⇒ ⇔ ° ‰ © ® ™ € £ ¥ ₪ • ▪ ■ □ ▢ ○ ● ◇ ◆ ✓ ✗'.split(' ');
  const emoji = '😀 😃 😊 😂 🙂 😉 😍 🤔 😎 😢 😮 😴 👍 👎 👏 🙏 💪 ❤️ ⭐ ✅ ❌ ❗ ❓ 📌 📚 ✏️ 🎓 🎉 📅 🔔'.split(' ');
  function characters(title, choices) {
    byId('characters-title').textContent = title;
    byId('characters-list').replaceChildren(...choices.map(value => {
      const button = document.createElement('button'); button.textContent = value; button.title = value;
      button.onclick = () => { byId('characters').close(); insert(value); }; return button;
    }));
    byId('characters').showModal();
  }
  byId('symbols').onclick = () => characters('Special characters', symbols);
  byId('emoji').onclick = () => characters('Emoji', emoji);
  document.querySelectorAll('dialog .close').forEach(button => button.onclick = () => { button.closest('dialog').close(); restoreSelection(); });
  byId('find').onclick = () => { byId('search').showModal(); byId('query').focus(); };
  function textNodes() {
    const nodes = [], walker = document.createTreeWalker(editable(), NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) if (!walker.currentNode.parentElement.closest('math')) nodes.push(walker.currentNode);
    return nodes;
  }
  function rangeAt(start, end) {
    const range = document.createRange(); let offset = 0, started = false;
    for (const node of textNodes()) {
      const next = offset + node.length;
      if (!started && start <= next) { range.setStart(node, start - offset); started = true; }
      if (end <= next) { range.setEnd(node, end - offset); return range; }
      offset = next;
    }
    return null;
  }
  let found = -1;
  function matches() {
    const sensitive = byId('match-case').checked;
    let text = textNodes().map(node => node.data).join(''), query = byId('query').value;
    if (!query) return [];
    if (!sensitive) { text = text.toLowerCase(); query = query.toLowerCase(); }
    const positions = []; let start = 0, index;
    while ((index = text.indexOf(query, start)) !== -1) { positions.push(index); start = index + query.length; }
    return positions;
  }
  byId('query').oninput = () => { found = -1; };
  byId('find-next').onclick = () => {
    const positions = matches(); found = positions.find(index => index > found) ?? positions[0] ?? -1;
    byId('search-status').textContent = positions.length ? `${positions.length} matches` : 'No matches';
    if (found < 0) return;
    selection = rangeAt(found, found + byId('query').value.length);
    byId('search').close(); restoreSelection(); selection.startContainer.parentElement.scrollIntoView({block:'nearest'});
  };
  function replaceAt(start) {
    const range = rangeAt(start, start + byId('query').value.length);
    if (range) { range.deleteContents(); range.insertNode(document.createTextNode(byId('replacement').value)); }
  }
  byId('replace').onclick = () => { const positions = matches(); if (positions.length) { replaceAt(positions.includes(found) ? found : positions[0]); changed(); } };
  byId('replace-all').onclick = () => { const positions = matches(); positions.reverse().forEach(replaceAt); changed(); byId('search-status').textContent = `Replaced ${positions.length} matches`; found = -1; };
})();
