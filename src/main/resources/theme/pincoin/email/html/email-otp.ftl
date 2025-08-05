<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${realmDisplayName} 인증 코드</title>
    <style>
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            line-height: 1.6;
            color: #333;
            max-width: 600px;
            margin: 0 auto;
            padding: 20px;
            background-color: #f4f4f4;
        }

        .container {
            background-color: white;
            padding: 40px;
            border-radius: 10px;
            box-shadow: 0 2px 10px rgba(0, 0, 0, 0.1);
        }

        .header {
            text-align: center;
            border-bottom: 2px solid #007bff;
            padding-bottom: 20px;
            margin-bottom: 30px;
        }

        .header h1 {
            color: #007bff;
            margin: 0;
            font-size: 28px;
        }

        .otp-section {
            text-align: center;
            margin: 30px 0;
            padding: 20px;
            background-color: #f8f9fa;
            border-radius: 8px;
            border-left: 4px solid #007bff;
        }

        .otp-code {
            font-size: 36px;
            font-weight: bold;
            color: #007bff;
            letter-spacing: 8px;
            margin: 20px 0;
            font-family: 'Courier New', monospace;
            background-color: white;
            padding: 15px 20px;
            border-radius: 5px;
            border: 2px dashed #007bff;
            display: inline-block;
        }

        .expiry-info {
            color: #dc3545;
            font-weight: bold;
            margin-top: 20px;
        }

        .instructions {
            background-color: #e7f3ff;
            padding: 20px;
            border-radius: 5px;
            margin: 20px 0;
        }

        .footer {
            margin-top: 30px;
            padding-top: 20px;
            border-top: 1px solid #dee2e6;
            font-size: 14px;
            color: #6c757d;
            text-align: center;
        }

        .security-notice {
            background-color: #fff3cd;
            border: 1px solid #ffeaa7;
            color: #856404;
            padding: 15px;
            border-radius: 5px;
            margin: 20px 0;
        }
    </style>
</head>
<body>
<div class="container">
    <div class="header">
        <h1>${realmDisplayName}</h1>
        <p>로그인 인증 코드</p>
    </div>

    <div class="instructions">
        <h3>🔐 로그인 인증 요청</h3>
        <p>안전한 로그인을 위해 아래 인증 코드를 입력해주세요.</p>
    </div>

    <div class="otp-section">
        <h2>인증 코드</h2>
        <div class="otp-code">${otp}</div>
        <div class="expiry-info">
            ⏰ 이 코드는 ${expiryMinutes}분 후에 만료됩니다
        </div>
    </div>

    <div class="security-notice">
        <strong>⚠️ 보안 안내</strong>
        <ul style="margin: 10px 0; padding-left: 20px;">
            <li>이 코드는 본인만 사용해야 합니다</li>
            <li>타인에게 절대 알려주지 마세요</li>
            <li>본인이 요청하지 않았다면 즉시 관리자에게 문의하세요</li>
        </ul>
    </div>

    <div class="footer">
        <p>이 이메일은 자동으로 발송되었습니다.</p>
        <p>문의사항이 있으시면 관리자에게 연락해주세요.</p>
        <hr style="margin: 20px 0;">
        <small>© ${realmDisplayName} - 보안 인증 시스템</small>
    </div>
</div>
</body>
</html>