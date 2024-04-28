// @see https://nginx.org/en/docs/njs/
// @see https://nginx.org/en/docs/njs/reference.html
function hello(r) {
    r.headersOut['x-njs'] = '1';
    r.return(200, `Hello world from njs v${njs.version}\n`);
}

export default {hello};
