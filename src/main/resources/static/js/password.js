/* Password helpers: a show/hide button on every password box, a strength bar and rule list on new passwords,
   a live "passwords match" message, and a button that suggests a strong password. No data leaves the page. */
(function () {
    'use strict';

    var RULES = [
        { test: function (p) { return p.length >= 9; }, text: 'At least 9 characters' },
        { test: function (p) { return /[A-Z]/.test(p); }, text: 'An uppercase letter' },
        { test: function (p) { return /[a-z]/.test(p); }, text: 'A lowercase letter' },
        { test: function (p) { return /[0-9]/.test(p); }, text: 'A digit' },
        { test: function (p) { return /[^A-Za-z0-9]/.test(p); }, text: 'A special character such as @ # $ %' }
    ];

    function make(tag, cls, text) {
        var el = document.createElement(tag);
        if (cls) { el.className = cls; }
        if (text) { el.textContent = text; }
        return el;
    }

    function addEye(input) {
        var wrap = make('span', 'pw-wrap');
        input.parentNode.insertBefore(wrap, input);
        wrap.appendChild(input);
        var eye = make('button', 'pw-eye', 'Show');
        eye.type = 'button';
        eye.setAttribute('aria-label', 'Show or hide the password');
        eye.addEventListener('click', function () {
            var hidden = input.type === 'password';
            input.type = hidden ? 'text' : 'password';
            eye.textContent = hidden ? 'Hide' : 'Show';
        });
        wrap.appendChild(eye);
    }

    function score(p) {
        var n = 0;
        RULES.forEach(function (r) { if (r.test(p)) { n++; } });
        return n;
    }

    function addStrength(input) {
        var box = make('div', 'pw-strength');
        var bar = make('div', 'pw-bar');
        var fill = make('div', 'pw-fill');
        bar.appendChild(fill);
        var label = make('div', 'pw-label meta');
        var list = make('ul', 'pw-rules');
        var items = RULES.map(function (r) { var li = make('li', '', r.text); list.appendChild(li); return li; });
        box.appendChild(bar); box.appendChild(label); box.appendChild(list);
        var host = input.closest('.pw-wrap') || input;
        host.parentNode.insertBefore(box, host.nextSibling);
        function update() {
            var p = input.value, s = score(p);
            fill.style.width = (s * 20) + '%';
            fill.className = 'pw-fill s' + s;
            label.textContent = p ? (s === 5 ? 'Strong password' : s >= 3 ? 'Almost there' : 'Too weak') : '';
            RULES.forEach(function (r, i) { items[i].className = r.test(p) ? 'ok' : ''; });
        }
        input.addEventListener('input', update);
        update();
    }

    function addMatch(input, first) {
        var msg = make('div', 'pw-match meta');
        var host = input.closest('.pw-wrap') || input;
        host.parentNode.insertBefore(msg, host.nextSibling);
        function update() {
            if (!input.value) { msg.textContent = ''; msg.className = 'pw-match meta'; return; }
            var same = input.value === first.value;
            msg.textContent = same ? 'Passwords match' : 'Passwords do not match yet';
            msg.className = 'pw-match meta ' + (same ? 'ok' : 'bad');
        }
        input.addEventListener('input', update);
        first.addEventListener('input', update);
    }

    function suggest() {
        var upper = 'ABCDEFGHJKLMNPQRSTUVWXYZ', lower = 'abcdefghijkmnopqrstuvwxyz', digits = '23456789', special = '@#$%&*!?';
        var all = upper + lower + digits + special;
        function pick(set) {
            var a = new Uint32Array(1);
            window.crypto.getRandomValues(a);
            return set.charAt(a[0] % set.length);
        }
        var chars = [pick(upper), pick(lower), pick(digits), pick(special)];
        while (chars.length < 14) { chars.push(pick(all)); }
        for (var i = chars.length - 1; i > 0; i--) {
            var r = new Uint32Array(1);
            window.crypto.getRandomValues(r);
            var j = r[0] % (i + 1), t = chars[i]; chars[i] = chars[j]; chars[j] = t;
        }
        return chars.join('');
    }

    document.addEventListener('DOMContentLoaded', function () {
        var fields = Array.prototype.slice.call(document.querySelectorAll('input[type=password]'));
        fields.forEach(function (input) {
            addEye(input);
            if (input.hasAttribute('data-new')) { addStrength(input); }
        });
        document.querySelectorAll('input[data-confirm]').forEach(function (input) {
            var first = document.getElementById(input.getAttribute('data-confirm'));
            if (first) { addMatch(input, first); }
        });
        document.querySelectorAll('button[data-suggest]').forEach(function (button) {
            button.addEventListener('click', function () {
                var first = document.getElementById(button.getAttribute('data-suggest'));
                var again = document.getElementById(first.getAttribute('data-partner'));
                var p = suggest();
                first.value = p; again.value = p;
                first.type = 'text'; again.type = 'text';
                first.dispatchEvent(new Event('input')); again.dispatchEvent(new Event('input'));
                var note = document.getElementById('pw-suggest-note');
                if (note) { note.textContent = 'Suggested: ' + p + ' (write it down or save it in your phone before continuing)'; }
            });
        });
    });
})();
