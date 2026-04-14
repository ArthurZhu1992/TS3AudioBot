(function () {
        const rememberKey = 'ts3ab.admin.remember';
        const usernameKey = 'ts3ab.admin.username';
        const $form = document.getElementById('admin-login-form');
        const $username = document.getElementById('username');
        const $remember = document.getElementById('remember-me');
        if (!$form || !$username || !$remember) {
            return;
        }
        const remembered = localStorage.getItem(rememberKey) === '1';
        $remember.checked = remembered;
        if (remembered) {
            const savedUser = localStorage.getItem(usernameKey) || '';
            if (savedUser) {
                $username.value = savedUser;
            }
        }
        $form.addEventListener('submit', function () {
            if ($remember.checked) {
                localStorage.setItem(rememberKey, '1');
                localStorage.setItem(usernameKey, $username.value || '');
            } else {
                localStorage.removeItem(rememberKey);
                localStorage.removeItem(usernameKey);
            }
        });
    })();
