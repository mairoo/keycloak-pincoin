<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('totp','userLabel') displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
    <#if section = "header">
        ${msg("smsOtpTitle")}
    <#elseif section = "form">
        <div id="kc-sms-otp-form">
            <p class="instruction">${msg("smsOtpInstructions", (phoneNumber!''))}</p>
            <p class="instruction">${msg("smsOtpExpiry", (expiryMinutes!'3'))}</p>

            <form id="kc-sms-otp-login-form" class="${properties.kcFormClass!}" action="${url.loginAction}"
                  method="post">
                <div class="${properties.kcFormGroupClass!}">
                    <div class="${properties.kcLabelWrapperClass!}">
                        <label for="otp" class="${properties.kcLabelClass!}">${msg("smsOtpCodeLabel")}</label>
                    </div>
                    <div class="${properties.kcInputWrapperClass!}">
                        <input id="otp" name="otp" type="text" class="${properties.kcInputClass!}"
                               maxlength="8" placeholder="${msg('smsOtpPlaceholder')}"
                               autocomplete="off" autofocus required
                               inputmode="numeric" pattern="[0-9]*"/>
                    </div>
                </div>

                <div class="${properties.kcFormGroupClass!} ${properties.kcFormSettingClass!}">
                    <div id="kc-form-options">
                        <div class="${properties.kcFormOptionsWrapperClass!}">
                            <button type="submit" name="action" value="resend"
                                    class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}">
                                ${msg("smsOtpResend")}
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
            // SMS OTP 입력 시 숫자만 허용하고 자동 포커스
            const otpInput = document.getElementById('otp');

            // 숫자만 입력 허용
            otpInput.addEventListener('input', function (e) {
                e.target.value = e.target.value.replace(/[^0-9]/g, '');

                // 최대 길이 도달 시 자동 제출 (선택적)
                if (e.target.value.length === parseInt(e.target.getAttribute('maxlength'))) {
                    // 잠시 후 자동 제출 (사용자가 확인할 시간 제공)
                    setTimeout(() => {
                        if (confirm('입력하신 인증 코드로 확인하시겠습니까?')) {
                            document.getElementById('kc-sms-otp-login-form').submit();
                        }
                    }, 500);
                }
            });

            // Enter 키로 제출
            otpInput.addEventListener('keypress', function (e) {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    document.getElementById('kc-sms-otp-login-form').submit();
                }
            });

            // 붙여넣기 시에도 숫자만 허용
            otpInput.addEventListener('paste', function (e) {
                e.preventDefault();
                const paste = (e.clipboardData || window.clipboardData).getData('text');
                const numbersOnly = paste.replace(/[^0-9]/g, '');
                const maxLength = parseInt(e.target.getAttribute('maxlength'));
                e.target.value = numbersOnly.substring(0, maxLength);

                // 붙여넣기 후 자동 제출 고려
                if (numbersOnly.length >= maxLength) {
                    setTimeout(() => {
                        if (confirm('붙여넣은 인증 코드로 확인하시겠습니까?')) {
                            document.getElementById('kc-sms-otp-login-form').submit();
                        }
                    }, 500);
                }
            });

            // 재발송 버튼 쿨다운 처리
            const resendButton = document.querySelector('button[value="resend"]');
            if (resendButton) {
                resendButton.addEventListener('click', function (e) {
                    // 재발송 후 버튼 비활성화
                    setTimeout(() => {
                        this.disabled = true;
                        this.textContent = '잠시 후 재발송 가능';

                        // 60초 후 다시 활성화
                        setTimeout(() => {
                            this.disabled = false;
                            this.textContent = '${msg("smsOtpResend")}';
                        }, 60000);
                    }, 1000);
                });
            }

            // 페이지 로드 시 OTP 입력 필드에 포커스
            window.addEventListener('load', function () {
                otpInput.focus();
            });
        </script>

        <style>
            /* SMS OTP 전용 스타일 */
            #otp {
                text-align: center;
                font-size: 24px;
                font-weight: bold;
                letter-spacing: 4px;
                font-family: 'Courier New', monospace;
                background-color: #f8f9fa;
                border: 2px solid #007bff;
                border-radius: 8px;
                padding: 15px;
                margin: 10px 0;
            }

            #otp:focus {
                outline: none;
                border-color: #0056b3;
                box-shadow: 0 0 0 3px rgba(0, 123, 255, 0.25);
                background-color: white;
            }

            .instruction {
                text-align: center;
                margin: 15px 0;
                padding: 10px;
                background-color: #e7f3ff;
                border-radius: 5px;
                border-left: 4px solid #007bff;
            }

            /* 모바일 최적화 */
            @media (max-width: 768px) {
                #otp {
                    font-size: 20px;
                    letter-spacing: 2px;
                }

                .instruction {
                    font-size: 14px;
                }
            }

            /* 재발송 버튼 스타일 */
            button[value="resend"]:disabled {
                opacity: 0.6;
                cursor: not-allowed;
            }

            /* 접근성 개선 */
            #otp[aria-invalid="true"] {
                border-color: #dc3545;
                background-color: #f8d7da;
            }
        </style>
    </#if>
</@layout.registrationLayout>