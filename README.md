## Hodlinvoice eclair plugin

This is a plugin for [eclair](github.com/ACINQ/eclair) that implements the [hodl-invoice](https://wiki.ion.radar.tech/tech/research/hodl-invoice) 
feature. A `hodl-invoice` is a BOLT-11 invoice that triggers a particular payment flow on the recipient's end, instead
of being immediately fulfilled the recipient has control over the settlement and can decide when and if to settle it.
The plugin only works on incoming payments directed to this node, not relayed payments.

### Installation
The plugin can be built locally or downloaded from the release page of this repo, it's a fat jar that must be 
passed as argument to eclair when it's launched, see the [instructions](https://github.com/ACINQ/eclair#plugins).

### Usage
The plugin exposes a new http interface with 2 additional endpoints that you can use to accept/reject incoming payments:

|              | METHOD | Parameter (form_data) | Action                                                      |
|--------------|--------|-----------------------|-------------------------------------------------------------|
| /hodlaccept | POST   | paymentHash          | Accepts the incoming payment by releasing its preimage      |
| /hodlreject | POST   | paymentHash          | Rejects the incoming payment by failing the underlying HTLC |

Note that it's currently impossible for eclair plugins to add new HTTP-RPC endpoints, so this plugin listens
on a different socket than the standard eclair API. You can configure the port by setting the
configuration key `hodlplugin.api.port` but if none is set the plugin will default to the eclair API port + 1.
The same authentication credentials of the regular eclair API is necessary to use the hodl plugin APIs.
The plugin doesn't persist the data between restarts, so if you have an invoice on hold it will be rejected when you 
restart eclair.