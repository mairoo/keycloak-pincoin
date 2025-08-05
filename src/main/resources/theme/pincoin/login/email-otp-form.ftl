<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('totp','userLabel') displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
    <#if section = "header">
        ${msg("emailOtpTitle")}
    <#elseif section = "form">
        <div id="kc-otp-form">
            <p class="instruction">${msg("emailOtpInstructions", (email!''))}</p>
            <p class="instruction">${msg("emailOtpExpiry", (expiryMinutes!'5'))}</p>

            <form id="kc-otp-login-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
                <div class="${properties.kcFormGroupClass!}">
                    <div class="${properties.kcLabelWrapperClass!}">
                        <label for="otp" class="${properties.kcLabelClass!}">${msg("emailOtpCodeLabel")}</label>
                    </div>
                    <div class="${properties.kcInputWrapperClass!}">
                        <input id="otp" name="otp" type="text" class="${properties.kcInputClass!}"
                               maxlength="8" placeholder="${msg('emailOtpPlaceholder')}"
                               autocomplete="off" autofocus required/>
                    </div>
                </div>

                <div class="${properties.kcFormGroupClass!} ${properties.kcFormSettingClass!}">
                    <div id="kc-form-options">
                        <div class="${properties.kcFormOptionsWrapperClass!}">
                            <button type="submit" name="action" value="resend"
                                    class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}">
                                ${msg("emailOtpResend")}
                            </button>
                        </div>
                    </div>

                    <div id="kc-form-buttons">
                        <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                               type="submit" value="${msg('doSubmit')}"/>
                    </div>
                </div>
            </form>
        </div>

        <script>
            // OTP 입력 시 자동 포커스 및 숫자만 입력 허용
            document.getElementById('otp').addEventListener('input', function (e) {
                e.target.value = e.target.value.replace(/[^0-9]/g, '');
            });

            // Enter 키로 제출
            document.getElementById('otp').addEventListener('keypress', function (e) {
                if (e.key === 'Enter') {
                    document.getElementById('kc-otp-login-form').submit();
                }
            });
        </script>
    </#if>
</@layout.registrationLayout>