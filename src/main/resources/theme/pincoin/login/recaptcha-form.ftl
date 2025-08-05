<#import "template.ftl" as layout>

<@layout.registrationLayout displayMessage=!messagesPerField.existsError('recaptcha'); section>
    <#if section = "header">
        ${msg("recaptcha.title","보안 검증")}
    <#elseif section = "form">

        <form id="kc-recaptcha-form" action="${url.loginAction}" method="post">
            <div>Site Key: ${recaptchaSiteKey!""}</div>
            <div>Version: ${recaptchaVersion!"v2"}</div>

            <#if recaptchaVersion?? && recaptchaVersion == "v3">
                <input type="hidden" id="g-recaptcha-response" name="g-recaptcha-response"/>
                <div id="status">v3 로딩 중...</div>
            <#else>
                <div id="recaptcha-container"></div>
                <div id="status">v2 로딩 중...</div>
            </#if>

            <input name="login" id="kc-login" type="submit" value="확인" disabled/>
        </form>

        <script>
            console.log('=== reCAPTCHA 디버깅 시작 ===');

            var siteKey = '${recaptchaSiteKey!""}';
            var version = '${recaptchaVersion!"v2"}';

            console.log('Site Key:', siteKey);
            console.log('Version:', version);

            function updateStatus(msg) {
                var el = document.getElementById('status');
                if (el) el.textContent = msg;
                console.log('[상태]', msg);
            }

            if (!siteKey) {
                updateStatus('❌ Site Key가 없습니다');
                console.error('Site Key가 설정되지 않았습니다');
            } else {
                updateStatus('스크립트 로드 시작...');
                console.log('스크립트 로드 시작');

                var script = document.createElement('script');
                script.src = version === 'v3'
                    ? 'https://www.google.com/recaptcha/api.js?render=' + siteKey
                    : 'https://www.google.com/recaptcha/api.js';
                script.async = true;
                script.defer = true;

                script.onload = function () {
                    console.log('✅ 스크립트 로드 성공');
                    updateStatus('스크립트 로드 완료');

                    // grecaptcha 객체 확인
                    if (typeof grecaptcha === 'undefined') {
                        console.error('❌ grecaptcha 객체가 없습니다');
                        updateStatus('❌ grecaptcha 로드 실패');
                        return;
                    }

                    console.log('grecaptcha 객체:', grecaptcha);

                    if (version === 'v3') {
                        console.log('v3 초기화 시작');
                        updateStatus('v3 초기화 중...');

                        grecaptcha.ready(function () {
                            console.log('✅ grecaptcha.ready 호출됨');
                            updateStatus('토큰 생성 중...');

                            grecaptcha.execute(siteKey, {action: 'login'})
                                .then(function (token) {
                                    console.log('✅ 토큰 생성 성공:', token.substring(0, 20) + '...');
                                    document.getElementById('g-recaptcha-response').value = token;
                                    document.getElementById('kc-login').disabled = false;
                                    updateStatus('✅ v3 준비 완료');
                                })
                                .catch(function (error) {
                                    console.error('❌ 토큰 생성 실패:', error);
                                    updateStatus('❌ 토큰 생성 실패: ' + error.message);
                                });
                        });
                    } else {
                        console.log('v2 렌더링 시작');
                        updateStatus('v2 렌더링 중...');

                        try {
                            grecaptcha.render('recaptcha-container', {
                                sitekey: siteKey,
                                callback: function (response) {
                                    console.log('✅ v2 검증 완료:', response.substring(0, 20) + '...');
                                    document.getElementById('kc-login').disabled = false;
                                    updateStatus('✅ v2 검증 완료');
                                },
                                'error-callback': function () {
                                    console.error('❌ v2 오류 발생');
                                    updateStatus('❌ v2 오류 발생');
                                }
                            });
                            updateStatus('v2 체크박스를 완료하세요');
                        } catch (error) {
                            console.error('❌ v2 렌더링 실패:', error);
                            updateStatus('❌ v2 렌더링 실패: ' + error.message);
                        }
                    }
                };

                script.onerror = function (error) {
                    console.error('❌ 스크립트 로드 실패:', error);
                    updateStatus('❌ 스크립트 로드 실패');
                };

                console.log('스크립트 추가:', script.src);
                document.head.appendChild(script);
            }
        </script>

    <#elseif section = "info">
        <span>보안 검증</span>
    </#if>
</@layout.registrationLayout>