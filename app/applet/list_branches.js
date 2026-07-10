const https = require('https');

const options = {
    hostname: 'api.github.com',
    path: '/repos/dokurokaruto-tech/Android-toolkits-aistudio/commits',
    headers: {
        'User-Agent': 'NodeJS-Agent'
    }
};

https.get(options, (res) => {
    let data = '';
    res.on('data', (chunk) => { data += chunk; });
    res.on('end', () => {
        try {
            const commits = JSON.parse(data);
            console.log("=== Commits in GitHub ===");
            if (Array.isArray(commits)) {
                commits.forEach(c => {
                    console.log(`- [${c.sha.substring(0,7)}] ${c.commit.message} by ${c.commit.author?.name || 'unknown'} (${c.commit.author?.date || 'no date'})`);
                });
            } else {
                console.log(commits);
            }
        } catch (e) {
            console.error("Parse error:", e.message);
            console.log("Raw response:", data);
        }
    });
}).on('error', (err) => {
    console.error("Request error:", err.message);
});
