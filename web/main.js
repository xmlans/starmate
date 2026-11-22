// 星伴 Starmate 介绍页的小脚本：浅色/深色切换、导航栏阴影、进场动画，
// 以及从 GitHub Releases 读取最新安装包，把下载按钮直接指向 APK。
// 读不到 GitHub（离线、被限流、仓库还没发版）时，按钮保持指向 Releases 页面。
(function () {
  'use strict';

  var REPO = 'xmlans/starmate';
  var root = document.documentElement;

  // ---------- 浅色 / 深色 ----------
  var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  var toggle = document.querySelector('[data-theme-toggle]');

  function isDark() {
    var forced = root.getAttribute('data-theme');
    if (forced) return forced === 'dark';
    return !!(media && media.matches);
  }

  function syncToggleLabel() {
    if (toggle) toggle.setAttribute('aria-label', isDark() ? '切换到浅色' : '切换到深色');
  }

  if (toggle) {
    toggle.addEventListener('click', function () {
      var next = isDark() ? 'light' : 'dark';
      root.setAttribute('data-theme', next);
      try { localStorage.setItem('starmate-theme', next); } catch (e) {}
      syncToggleLabel();
    });
  }
  if (media && media.addEventListener) media.addEventListener('change', syncToggleLabel);
  syncToggleLabel();

  // ---------- 导航栏：滚动后显示分隔线 ----------
  var nav = document.querySelector('.nav');
  function onScroll() {
    if (nav) nav.classList.toggle('scrolled', window.scrollY > 8);
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  // ---------- 进场动画 ----------
  var reveals = document.querySelectorAll('.reveal');
  if ('IntersectionObserver' in window) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('in');
          io.unobserve(entry.target);
        }
      });
    }, { rootMargin: '0px 0px -8% 0px' });
    reveals.forEach(function (el) { io.observe(el); });
  } else {
    reveals.forEach(function (el) { el.classList.add('in'); });
  }

  // ---------- 下载：读取 GitHub Releases ----------
  function card(kind) { return document.querySelector('[data-edition="' + kind + '"]'); }

  function setMeta(kind, text) {
    var el = card(kind) && card(kind).querySelector('[data-meta]');
    if (el) el.textContent = text;
  }

  function formatDate(iso) {
    var d = new Date(iso);
    if (isNaN(d.getTime())) return '';
    return d.getFullYear() + ' 年 ' + (d.getMonth() + 1) + ' 月 ' + d.getDate() + ' 日';
  }

  function fill(kind, release, asset) {
    var el = card(kind);
    if (!el) return;
    var link = el.querySelector('[data-dl]');
    // 只接受 GitHub 自己的下载地址，防止把按钮指向别处
    if (link && /^https:\/\/github\.com\//.test(asset.browser_download_url)) {
      link.href = asset.browser_download_url;
    }
    var parts = [release.tag_name || release.name || ''];
    if (asset.size) parts.push(Math.round(asset.size / 1048576) + ' MB');
    var date = formatDate(release.published_at);
    if (date) parts.push(date);
    setMeta(kind, parts.filter(Boolean).join(' · '));

    var digest = typeof asset.digest === 'string' ? asset.digest : '';
    if (digest.indexOf('sha256:') === 0) {
      var sum = el.querySelector('[data-sum]');
      var value = el.querySelector('[data-sum-value]');
      if (sum && value) {
        value.textContent = digest.slice(7);
        sum.hidden = false;
      }
    }
  }

  function editionOf(name) {
    if (/beta/i.test(name)) return 'beta';
    if (/play/i.test(name)) return 'play';
    return 'stable';
  }

  function loadReleases() {
    if (!window.fetch) return;
    fetch('https://api.github.com/repos/' + REPO + '/releases?per_page=30', {
      headers: { Accept: 'application/vnd.github+json' }
    })
      .then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      })
      .then(function (releases) {
        var found = {};
        // 列表按发布时间从新到旧排列，取每个版本最新的那个 APK。
        // 正式版只从正式发布里找；Beta 版的安装包无论放在预发布还是正式发布里都算。
        (Array.isArray(releases) ? releases : []).forEach(function (release) {
          if (release.draft) return;
          (release.assets || []).forEach(function (asset) {
            if (!/\.apk$/i.test(asset.name || '')) return;
            var kind = editionOf(asset.name);
            if (found[kind]) return;
            if (kind === 'stable' && release.prerelease) return;
            found[kind] = { release: release, asset: asset };
          });
        });
        ['stable', 'beta'].forEach(function (kind) {
          if (found[kind]) fill(kind, found[kind].release, found[kind].asset);
          else setMeta(kind, '还没有发布，点按钮去 Releases 页面看看');
        });
      })
      .catch(function () {
        ['stable', 'beta'].forEach(function (kind) {
          setMeta(kind, '暂时读不到版本信息，点按钮去 Releases 页面下载');
        });
      });
  }

  function loadStars() {
    if (!window.fetch) return;
    var badge = document.querySelector('[data-stars]');
    if (!badge) return;
    fetch('https://api.github.com/repos/' + REPO, { headers: { Accept: 'application/vnd.github+json' } })
      .then(function (res) { return res.ok ? res.json() : null; })
      .then(function (repo) {
        var n = repo && repo.stargazers_count;
        if (typeof n === 'number' && n > 0) {
          badge.textContent = '★ ' + (n >= 1000 ? (n / 1000).toFixed(1) + 'k' : n);
          badge.hidden = false;
        }
      })
      .catch(function () {});
  }

  // ---------- 复制校验值 ----------
  document.addEventListener('click', function (event) {
    var btn = event.target.closest && event.target.closest('[data-copy]');
    if (!btn) return;
    var code = btn.parentNode.querySelector('code');
    if (!code || !navigator.clipboard) return;
    navigator.clipboard.writeText(code.textContent).then(function () {
      btn.classList.add('done');
      btn.setAttribute('aria-label', '已复制');
      setTimeout(function () {
        btn.classList.remove('done');
        btn.setAttribute('aria-label', '复制校验值');
      }, 1600);
    }).catch(function () {});
  });

  loadReleases();
  loadStars();
})();
