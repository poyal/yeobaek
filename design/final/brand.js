/* Approved Yeobaek brand: icon study 03, candidate 04 “리듬”. */
(() => {
  const path='M27 50L43 26C46 22 52 23 54 28L61 45L70 32C73 28 78 29 80 33L84 41C86 44 85 47 83 50L65 78C62 83 56 82 54 77L47 60L39 73C36 77 31 76 29 72L25 64C23 60 24 55 27 50Z';
  const palette=Object.freeze({
    light:{background:'#536348',foreground:'#F8F5EF'},
    dark:{background:'#292F25',foreground:'#E3EAD8'}
  });
  const svg=(mode='light',kind='icon')=>{
    const colors=palette[mode]||palette.light;
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" role="img" aria-label="여백 · 리듬"><title>여백 · 리듬</title>${kind==='icon'?`<rect width="108" height="108" fill="${colors.background}"/>`:''}<path fill="${kind==='monochrome'?'#000000':colors.foreground}" d="${path}"/></svg>`;
  };
  // Tight viewBox is only for inline wordmarks; exported launcher layers remain 108×108.
  const mark=(className='')=>`<svg class="brand-symbol ${className}" viewBox="18 18 72 72" fill="currentColor" aria-hidden="true"><path d="${path}"/></svg>`;
  window.YeobaekBrand=Object.freeze({name:'여백',symbol:'리듬',path,palette,svg,mark});
  if(typeof document!=='undefined'){
    document.querySelectorAll('[data-brand-symbol]').forEach(element=>{element.innerHTML=mark();});
  }
})();
