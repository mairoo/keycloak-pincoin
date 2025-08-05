<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=!messagesPerField.existsError('recaptcha'); section>
    <#if section = "header">
        ${msg("recaptcha.title","안전한 로그인을 위한 확인")}
    <#elseif section = "form">

        <form id="kc-recaptcha-form" action="${url.loginAction}" method="post">
            <#if recaptchaVersion?? && recaptchaVersion == "v3">
                <input type="hidden" id="g-recaptcha-response" name="g-recaptcha-response"/>
                <div id="status">reCAPTCHA v3 로딩 중...</div>
            <#else>
                <div id="recaptcha-container"></div>
                <div id="status">reCAPTCHA v2 로딩 중...</div>
            </#if>

            <input name="login" id="kc-login" type="submit" value="확인" disabled/>
        </form>

        <script>
            var siteKey = '${recaptchaSiteKey!""}';
            var version = '${recaptchaVersion!"v2"}';

            function updateStatus(msg) {
                var el = document.getElementById('status');
                if (el) el.textContent = msg;
            }

            if (!siteKey) {
                updateStatus('❌ Site Key가 설정되지 않았습니다');
            } else {
                var script = document.createElement('script');
                script.src = version === 'v3'
                    ? 'https://www.google.com/recaptcha/api.js?render=' + siteKey
                    : 'https://www.google.com/recaptcha/api.js';
                script.async = true;
                script.defer = true;

                script.onload = function () {
                    if (typeof grecaptcha === 'undefined') {
                        updateStatus('❌ reCAPTCHA 로드 실패');
                        return;
                    }

                    if (version === 'v3') {
                        grecaptcha.ready(function () {
                            updateStatus('토큰 생성 중...');

                            grecaptcha.execute(siteKey, {action: 'login'})
                                .then(function (token) {
                                    document.getElementById('g-recaptcha-response').value = token;
                                    document.getElementById('kc-login').disabled = false;
                                    updateStatus('✅ 보안 검증 완료');
                                })
                                .catch(function (error) {
                                    updateStatus('❌ 토큰 생성 실패');
                                });
                        });
                    } else {
                        try {
                            grecaptcha.render('recaptcha-container', {
                                sitekey: siteKey,
                                callback: function (response) {
                                    document.getElementById('kc-login').disabled = false;
                                    updateStatus('✅ 보안 검증 완료');
                                },
                                'error-callback': function () {
                                    updateStatus('❌ 검증 오류 발생');
                                }
                            });
                            updateStatus('체크박스를 완료하세요');
                        } catch (error) {
                            updateStatus('❌ 렌더링 실패');
                        }
                    }
                };

                script.onerror = function () {
                    updateStatus('❌ 스크립트 로드 실패');
                };

                document.head.appendChild(script);
            }
        </script>

    <#elseif section = "info">
        <span>보안 검증</span>
    </#if>
</@layout.registrationLayout>