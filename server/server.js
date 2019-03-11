var app = require('express')();
var http = require('http').Server(app);
var io = require('socket.io')(http);

var roomId = 'ultraRoom';

io.on('connection', function (socket) {
  
    console.log("Connected! sck-id:", socket.id);
    socket.join(roomId); // Autojoin room.
    
    socket.on('message', function (data) {
  		console.log('message', data);
  		socket.broadcast.to(roomId).emit('message', data);
	  });
	  
	  socket.on('disconnect', function () {
      console.log("Disconnected! sck-id:", socket.id);
    });
    
});

http.listen(process.env.PORT || 3000, process.env.IP || "0.0.0.0", function(){
  var addr = http.address();
  console.log("Chat server listening at", addr.address + ":" + addr.port);
});
